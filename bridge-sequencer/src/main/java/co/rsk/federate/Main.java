/*
 * This file is part of RskJ
 * Copyright (C) 2018 RSK Labs Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package co.rsk.federate;

import java.nio.file.Path;
import java.util.List;

import co.rsk.federate.config.SequencerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts a bridge sequencer.
 *
 * <pre>
 *   bmb-sequencer &lt;config file&gt;
 *   bmb-sequencer --check &lt;config file&gt;
 * </pre>
 *
 * <p>One argument, because everything else belongs in the file: the same operator runs this for
 * months and a flag is a thing that gets lost between restarts.
 *
 * <p>{@code --check} reads the configuration and the keys, says what is wrong, and exits without
 * connecting to anything. Worth having separately: the answer to "are these key files right" should
 * not require a running node and a bitcoin peer.
 */
public final class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || args.length > 2 || (args.length == 2 && !"--check".equals(args[0]))) {
            System.err.println("usage: bmb-sequencer [--check] <config file>");
            System.exit(2);
            return;
        }

        boolean checkOnly = args.length == 2;
        Path configFile = Path.of(args[checkOnly ? 1 : 0]);

        SequencerConfig config;
        try {
            config = SequencerConfig.from(configFile);
        } catch (RuntimeException e) {
            System.err.println("Cannot read " + configFile + ": " + e.getMessage());
            System.exit(2);
            return;
        }

        SequencerRunner runner = new SequencerRunner(config);

        if (checkOnly) {
            List<String> problems = runner.check();
            if (problems.isEmpty()) {
                System.out.println("Configuration and keys look usable.");
                System.exit(0);
            } else {
                problems.forEach(problem -> System.err.println("  " + problem));
                System.exit(1);
            }
            return;
        }

        // Ordinary shutdown, so that the bitcoin peer closes its store rather than leaving it to
        // be recovered on the next start.
        Runtime.getRuntime().addShutdownHook(new Thread(runner::close, "sequencer-shutdown"));

        // Stopping because the node went away is a failure, not an ordinary exit. Saying so lets
        // a supervisor restart this rather than treat it as a job that finished.
        runner.onStopped(() -> {
            logger.error("[main] Stopped because the node could not be reached");
            Runtime.getRuntime().halt(1);
        });

        try {
            runner.start();
        } catch (Exception e) {
            logger.error("[main] Could not start: {}", e.getMessage(), e);
            runner.close();
            System.exit(1);
        }
    }
}
