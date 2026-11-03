package ds.banking.protocol;

import java.util.regex.Pattern;

/** Account identifier format: 1–64 characters of {@code [A-Za-z0-9_-]} (data-model.md, FR-005). */
public final class AccountIds {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private AccountIds() {
    }

    public static boolean isValid(String accountId) {
        return accountId != null && FORMAT.matcher(accountId).matches();
    }
}
