package com.developi.jnx.utils;

import com.hcl.domino.DominoClient;
import com.hcl.domino.DominoClientBuilder;
import com.hcl.domino.DominoProcess;
import com.hcl.domino.commons.util.StringUtil;
import java.nio.file.Paths;
import java.util.function.Consumer;

public class DominoRunner {

    public static void runWithDominoClient(Consumer<DominoClient> action) {
        runWithDominoClient(action, true);
    }

    public static void runWithDominoClient(Consumer<DominoClient> action, boolean useExistingNotesClientId) {

        // Set the jnx.skipNotesThread property to true to avoid creating a NotesThread.
        // Otherwise, we are going to spend precious time to find a non-error exception!
        System.setProperty("jnx.skipNotesThread", "true");

        //System.setProperty("jnx.debuginit", "true");

        // Although the documentation suggests a single string argument, we use an array.
        // The second parameter would be the notes.ini file path, but we don't need it, I guess.
        String[] initArgs = new String[]{System.getenv("Notes_ExecDirectory")};

        final DominoProcess dp = DominoProcess.get();

        dp.initializeProcess(initArgs);

        try (DominoProcess.DominoThreadContext ignored = dp.initializeThread()) {
            if (!useExistingNotesClientId) {
                // prevent ID password prompt
                final String idFilePath = System.getenv("Notes_IDPath");
                final String idPassword = System.getenv("Notes_IDPassword");

                if (!StringUtil.isEmpty(idPassword)) {
                    dp.switchToId(StringUtil.isEmpty(idFilePath) ? null : Paths.get(idFilePath), idPassword, true);
                }
            }

            try (DominoClient dc = DominoClientBuilder.newDominoClient().asIDUser().build()) {
                action.accept(dc);
            }

        } catch (Throwable t) {
            throw new RuntimeException("Domino Process Failed", t);
        } finally {
            dp.terminateProcess();
        }

    }
}
