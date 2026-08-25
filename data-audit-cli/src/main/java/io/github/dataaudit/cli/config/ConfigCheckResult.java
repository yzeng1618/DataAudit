// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.cli.config;

import java.util.ArrayList;
import java.util.List;

public class ConfigCheckResult {
    public String status = "ok";
    public List<Check> checks = new ArrayList<>();
    public List<String> errors = new ArrayList<>();

    public void addCheck(String name, String checkStatus, String message) {
        Check check = new Check();
        check.name = name;
        check.status = checkStatus;
        check.message = message;
        checks.add(check);
        if ("error".equals(checkStatus)) {
            status = "error";
            errors.add(message);
        }
    }

    public boolean isOk() {
        return "ok".equals(status);
    }

    public static class Check {
        public String name;
        public String status;
        public String message;
    }
}
