// SPDX-License-Identifier: Apache-2.0
package io.github.dataaudit.cli;

import picocli.CommandLine;

public final class DataAuditAiMain {
    private DataAuditAiMain() {
    }

    public static void main(String[] args) {
        String[] delegated = new String[args.length + 1];
        delegated[0] = "ai";
        System.arraycopy(args, 0, delegated, 1, args.length);
        int exitCode = new CommandLine(new DataAuditMain()).execute(delegated);
        System.exit(exitCode);
    }
}
