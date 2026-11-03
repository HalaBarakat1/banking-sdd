package ds.banking.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ds.banking.protocol.InvalidRequestException;
import ds.banking.protocol.Operation;
import ds.banking.server.fault.FaultConfig;
import ds.banking.server.fault.FaultInjector;
import ds.banking.server.fault.FaultRule;
import ds.banking.server.fault.FaultScenario;
import ds.banking.server.fault.FaultTrigger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Fault rule validation and how the injector picks a fault (T036; FR-028, FR-029, FR-030, EC-012). */
@Tag("unit")
class FaultConfigTest {

    private static FaultRule next(FaultScenario scenario, int count) {
        Integer delay = scenario.name().startsWith("DELAY") ? 100 : null;
        return new FaultRule(scenario, FaultTrigger.NEXT_N, count, null, null, null, delay, null);
    }

    private static FaultRule chance(FaultScenario scenario, double p, String account, Operation operation) {
        Integer delay = scenario.name().startsWith("DELAY") ? 100 : null;
        return new FaultRule(scenario, FaultTrigger.PROBABILITY, null, p, account, operation, delay, null);
    }

    private static FaultConfig parse(String json) throws InvalidRequestException {
        return FaultConfig.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("FR-028: the five scenarios are FS-1 drop request, FS-2 crash, FS-3 drop response, FS-4a and FS-4b delays")
    void scenarios() {
        assertEquals(List.of(FaultScenario.DROP_REQUEST, FaultScenario.CRASH_BEFORE_EXECUTE, FaultScenario.DROP_RESPONSE,
                FaultScenario.DELAY_BEFORE_EXECUTE, FaultScenario.DELAY_AFTER_EXECUTE), List.of(FaultScenario.values()));
    }

    @Test
    @DisplayName("FR-029: a valid config is parsed from snake_case JSON; downtime_ms defaults to 1000")
    void parsesValidConfig() throws Exception {
        FaultConfig config = parse("{\"rules\":[{\"scenario\":\"CRASH_BEFORE_EXECUTE\",\"trigger\":\"NEXT_N\",\"count\":2},"
                + "{\"scenario\":\"DELAY_AFTER_EXECUTE\",\"trigger\":\"PROBABILITY\",\"probability\":0.25,"
                + "\"delay_ms\":600,\"account_id\":\"A\",\"operation\":\"DEBIT\"}],\"seed\":42}");

        assertEquals(2, config.rules().size());
        assertEquals(1000, config.rules().get(0).downtimeMs());
        assertEquals(new FaultRule(FaultScenario.DELAY_AFTER_EXECUTE, FaultTrigger.PROBABILITY, null, 0.25, "A",
                Operation.DEBIT, 600, null), config.rules().get(1));
        assertEquals(42L, config.seed());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "{\"scenario\":\"DROP_REQUEST\",\"trigger\":\"NEXT_N\"}",
        "{\"scenario\":\"DROP_REQUEST\",\"trigger\":\"NEXT_N\",\"count\":0}",
        "{\"scenario\":\"DROP_REQUEST\",\"trigger\":\"NEXT_N\",\"count\":10001}",
        "{\"scenario\":\"DROP_REQUEST\",\"trigger\":\"NEXT_N\",\"count\":1.5}",
        "{\"scenario\":\"DROP_REQUEST\",\"trigger\":\"NEXT_N\",\"count\":1,\"probability\":0.5}",
        "{\"scenario\":\"DROP_REQUEST\",\"trigger\":\"PROBABILITY\"}",
        "{\"scenario\":\"DROP_REQUEST\",\"trigger\":\"PROBABILITY\",\"probability\":0}",
        "{\"scenario\":\"DROP_REQUEST\",\"trigger\":\"PROBABILITY\",\"probability\":1.5}",
        "{\"scenario\":\"DROP_REQUEST\",\"trigger\":\"PROBABILITY\",\"probability\":0.5,\"count\":3}",
        "{\"scenario\":\"DELAY_BEFORE_EXECUTE\",\"trigger\":\"NEXT_N\",\"count\":1}",
        "{\"scenario\":\"DELAY_BEFORE_EXECUTE\",\"trigger\":\"NEXT_N\",\"count\":1,\"delay_ms\":0}",
        "{\"scenario\":\"DELAY_AFTER_EXECUTE\",\"trigger\":\"NEXT_N\",\"count\":1,\"delay_ms\":60001}",
        "{\"scenario\":\"DROP_RESPONSE\",\"trigger\":\"NEXT_N\",\"count\":1,\"delay_ms\":100}",
        "{\"scenario\":\"CRASH_BEFORE_EXECUTE\",\"trigger\":\"NEXT_N\",\"count\":1,\"downtime_ms\":60001}",
        "{\"scenario\":\"CRASH_BEFORE_EXECUTE\",\"trigger\":\"NEXT_N\",\"count\":1,\"downtime_ms\":-1}",
        "{\"scenario\":\"DROP_REQUEST\",\"trigger\":\"NEXT_N\",\"count\":1,\"downtime_ms\":10}",
        "{\"scenario\":\"DROP_REQUEST\",\"trigger\":\"NEXT_N\",\"count\":1,\"account_id\":\"a b\"}",
        "{\"scenario\":\"FLOOD\",\"trigger\":\"NEXT_N\",\"count\":1}",
        "{\"scenario\":\"DROP_REQUEST\",\"trigger\":\"ALWAYS\",\"count\":1}",
        "{\"trigger\":\"NEXT_N\",\"count\":1}",
        "{\"scenario\":\"DROP_REQUEST\",\"count\":1}",
        "{\"scenario\":\"DROP_REQUEST\",\"trigger\":\"NEXT_N\",\"count\":1,\"color\":\"red\"}"
    })
    @DisplayName("FR-029 / EC-012: each invalid rule is refused, using the limits of the data model")
    void invalidRuleRefused(String rule) {
        assertThrows(InvalidRequestException.class, () -> parse("{\"rules\":[" + rule + "]}"));
    }

    @Test
    @DisplayName("FR-029: probabilities of rules that can hit the same request must add up to at most 1")
    void overlappingProbabilities() {
        assertThrows(IllegalArgumentException.class, () -> new FaultConfig(List.of(
                chance(FaultScenario.DROP_REQUEST, 0.6, null, null),
                chance(FaultScenario.DROP_RESPONSE, 0.6, "A", null)), 1L));
        assertThrows(IllegalArgumentException.class, () -> new FaultConfig(List.of(
                chance(FaultScenario.DROP_REQUEST, 0.6, null, Operation.DEBIT),
                chance(FaultScenario.DROP_RESPONSE, 0.6, null, null)), 1L));
        new FaultConfig(List.of(
                chance(FaultScenario.DROP_REQUEST, 0.6, "A", null),
                chance(FaultScenario.DROP_RESPONSE, 0.6, "B", null),
                chance(FaultScenario.DROP_RESPONSE, 0.4, null, null)), 1L);
        new FaultConfig(List.of(
                chance(FaultScenario.DROP_REQUEST, 0.7, null, Operation.DEBIT),
                chance(FaultScenario.DROP_RESPONSE, 0.7, null, Operation.CANCEL)), 1L);
    }

    @Test
    @DisplayName("FR-030: with the default empty config no fault ever fires")
    void emptyNeverFires() {
        FaultInjector injector = new FaultInjector();

        for (int i = 0; i < 1000; i++) {
            assertEquals(Optional.empty(), injector.choose("A", Operation.DEBIT));
        }
        assertTrue(injector.config().rules().isEmpty());
    }

    @Test
    @DisplayName("FR-029: a request triggers at most one scenario: NEXT_N rules first, in order, then one draw")
    void atMostOneScenario() {
        FaultInjector injector = new FaultInjector();
        injector.configure(new FaultConfig(List.of(next(FaultScenario.DROP_REQUEST, 1),
                next(FaultScenario.DROP_RESPONSE, 1), chance(FaultScenario.DELAY_AFTER_EXECUTE, 1.0, null, null)), 7L));

        assertEquals(FaultScenario.DROP_REQUEST, injector.choose("A", Operation.DEBIT).orElseThrow().scenario());
        assertEquals(FaultScenario.DROP_RESPONSE, injector.choose("A", Operation.DEBIT).orElseThrow().scenario());
        assertEquals(FaultScenario.DELAY_AFTER_EXECUTE, injector.choose("A", Operation.DEBIT).orElseThrow().scenario());
    }

    @Test
    @DisplayName("FR-029: NEXT_N fires exactly count times, even with 50 threads asking at once")
    void nextNUnderConcurrency() throws Exception {
        FaultInjector injector = new FaultInjector();
        injector.configure(new FaultConfig(List.of(next(FaultScenario.DROP_REQUEST, 10)), null));
        ConcurrentLinkedQueue<FaultRule> fired = new ConcurrentLinkedQueue<>();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(50);
        for (int i = 0; i < 50; i++) {
            pool.submit(() -> {
                go.await();
                injector.choose("A", Operation.DEBIT).ifPresent(fired::add);
                return null;
            });
        }

        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(10, fired.size());
        assertEquals(Optional.empty(), injector.choose("A", Operation.DEBIT));
    }

    @Test
    @DisplayName("FR-029: probability draws are reproducible with a seed and hit about the configured rate")
    void probabilityIsReproducible() {
        FaultConfig config = new FaultConfig(List.of(chance(FaultScenario.DROP_REQUEST, 0.3, null, null)), 42L);
        FaultInjector first = new FaultInjector();
        FaultInjector second = new FaultInjector();
        first.configure(config);
        second.configure(config);
        List<Boolean> a = new ArrayList<>();
        List<Boolean> b = new ArrayList<>();

        for (int i = 0; i < 2000; i++) {
            a.add(first.choose("A", Operation.DEBIT).isPresent());
            b.add(second.choose("A", Operation.DEBIT).isPresent());
        }

        assertEquals(a, b);
        long hits = a.stream().filter(x -> x).count();
        assertTrue(hits > 480 && hits < 720, "hits " + hits);
    }

    @Test
    @DisplayName("FR-029: account and operation filters limit a rule to matching requests")
    void filters() {
        FaultInjector injector = new FaultInjector();
        injector.configure(new FaultConfig(List.of(
                new FaultRule(FaultScenario.DROP_REQUEST, FaultTrigger.NEXT_N, 5, null, "A", Operation.CANCEL, null, null)),
                null));

        assertEquals(Optional.empty(), injector.choose("B", Operation.CANCEL));
        assertEquals(Optional.empty(), injector.choose("A", Operation.DEBIT));
        assertTrue(injector.choose("A", Operation.CANCEL).isPresent());
    }

    @Test
    @DisplayName("EC-012 / FR-030: configure replaces the rules and their counters as a whole")
    void configureReplacesAtomically() {
        FaultInjector injector = new FaultInjector();
        injector.configure(new FaultConfig(List.of(next(FaultScenario.DROP_REQUEST, 1)), null));
        injector.choose("A", Operation.DEBIT);

        injector.configure(new FaultConfig(List.of(next(FaultScenario.DROP_REQUEST, 1)), null));

        assertTrue(injector.choose("A", Operation.DEBIT).isPresent());
        injector.configure(FaultConfig.empty());
        assertEquals(Optional.empty(), injector.choose("A", Operation.DEBIT));
    }
}
