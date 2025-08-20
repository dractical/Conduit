package test;

import com.dractical.conduit.*;

public class Demo {

    public static final class LoginAttempt implements Cancellable {
        private final String username;
        private boolean cancelled;

        public LoginAttempt(String username) {
            this.username = username;
        }

        public String username() {
            return username;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void setCancelled(boolean c) {
            this.cancelled = c;
        }

        @Override
        public String toString() {
            return "LoginAttempt{" + username + ", cancelled=" + cancelled + "}";
        }
    }

    public static final class LoginSucceeded implements Event {
        private final String username;

        public LoginSucceeded(String username) {
            this.username = username;
        }

        public String username() {
            return username;
        }
    }

    public static final class AuthListeners {
        @Subscribe(priority = Priority.HIGHEST)
        public void checkBanList(LoginAttempt e) {
            if ("bannedUser".equalsIgnoreCase(e.username())) {
                e.setCancelled(true);
            }
        }

        @Subscribe(ignoreCancelled = false)
        public void audit(LoginAttempt e) {
            System.out.println("[AUDIT] attempt by " + e.username() + " cancelled=" + e.isCancelled());
        }

        @Subscribe(once = true)
        public void welcome(LoginSucceeded e) {
            System.out.println("Welcome " + e.username() + "!");
        }
    }

    public static void main(String[] args) {
        EventBus bus = new EventBus();
        bus.register(new AuthListeners());

        LoginAttempt attempt = new LoginAttempt("bannedUser");
        bus.post(attempt);

        bus.post(new LoginSucceeded("alice"));
        bus.post(new LoginSucceeded("alice"));
    }
}
