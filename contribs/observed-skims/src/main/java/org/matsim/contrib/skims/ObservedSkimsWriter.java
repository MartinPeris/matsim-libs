package org.matsim.contrib.skims;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.controler.events.IterationEndsEvent;
import org.matsim.core.controler.listener.IterationEndsListener;
import org.matsim.core.utils.io.IOUtils;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Writes both skims to CSV at the end of each iteration, and logs a one-line summary of each.
 * <p>
 * Without this, a run in which the skims changed nothing looks exactly like a run in which they had
 * nothing to say, and the two call for opposite responses: the first means the cost channel is too
 * weak to matter, the second means the scenario's services run to time. Distinguishing them needs the
 * distribution of the excesses, not just the outcome.
 * <p>
 * Only bins with a published value are written, so the files stay proportional to what was observed
 * rather than to the size of the schedule. On a city network the difference is two orders of
 * magnitude.
 *
 * @author Monash Healthy Active Cities
 */
@Singleton
public final class ObservedSkimsWriter implements IterationEndsListener {

	private static final Logger log = LogManager.getLogger(ObservedSkimsWriter.class);

	public static final String WAIT_FILE = "observedTransitWaitSkim.csv";
	public static final String STOP_STOP_FILE = "observedTransitStopStopSkim.csv";

	private final OutputDirectoryHierarchy outputDirectoryHierarchy;
	private final ObservedTransitWaitTime waitSkim;
	private final ObservedTransitStopStopTime stopStopSkim;

	@Inject
	ObservedSkimsWriter(OutputDirectoryHierarchy outputDirectoryHierarchy, ObservedTransitWaitTime waitSkim,
			ObservedTransitStopStopTime stopStopSkim) {
		this.outputDirectoryHierarchy = outputDirectoryHierarchy;
		this.waitSkim = waitSkim;
		this.stopStopSkim = stopStopSkim;
	}

	@Override
	public void notifyIterationEnds(IterationEndsEvent event) {
		writeWaits(event.getIteration());
		writeStopStops(event.getIteration());
	}

	private void writeWaits(int iteration) {
		List<ObservedTransitWaitTime.SkimEntry> entries = waitSkim.entries();
		String file = outputDirectoryHierarchy.getIterationFilename(iteration, WAIT_FILE);
		try (BufferedWriter writer = IOUtils.getBufferedWriter(file)) {
			writer.write("line;route;stop;binStart;observations;observedWait;scheduledWait;excess\n");
			for (ObservedTransitWaitTime.SkimEntry e : entries) {
				writer.write(String.format("%s;%s;%s;%.0f;%d;%.2f;%.2f;%.2f%n", e.line(), e.route(), e.stop(),
						e.binStart(), e.observations(), e.observed(), e.scheduled(), e.excess()));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		logSummary("wait", iteration, entries.size(),
				entries.stream().mapToDouble(ObservedTransitWaitTime.SkimEntry::excess).toArray());
	}

	private void writeStopStops(int iteration) {
		List<ObservedTransitStopStopTime.SkimEntry> entries = stopStopSkim.entries();
		String file = outputDirectoryHierarchy.getIterationFilename(iteration, STOP_STOP_FILE);
		try (BufferedWriter writer = IOUtils.getBufferedWriter(file)) {
			writer.write("fromStop;toStop;binStart;observations;observedTime;scheduledTime;excess\n");
			for (ObservedTransitStopStopTime.SkimEntry e : entries) {
				writer.write(String.format("%s;%s;%.0f;%d;%.2f;%.2f;%.2f%n", e.fromStop(), e.toStop(),
						e.binStart(), e.observations(), e.observed(), e.scheduled(), e.excess()));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		logSummary("stop-to-stop", iteration, entries.size(),
				entries.stream().mapToDouble(ObservedTransitStopStopTime.SkimEntry::excess).toArray());
	}

	/**
	 * The mean alone would hide the thing worth knowing. A skim whose excesses are all near zero and
	 * one whose excesses cancel look identical in the mean and behave completely differently, so the
	 * share of bins running late and the largest excess are logged alongside it.
	 */
	private static void logSummary(String what, int iteration, int bins, double[] excesses) {
		if (excesses.length == 0) {
			log.info("[it.{}] {} skim: nothing observed yet", iteration, what);
			return;
		}
		double sum = 0;
		double max = Double.NEGATIVE_INFINITY;
		int late = 0;
		for (double excess : excesses) {
			sum += excess;
			max = Math.max(max, excess);
			if (excess > 0) {
				late++;
			}
		}
		log.info("[it.{}] {} skim: {} published bins, mean excess {}s, {}% of bins slower than the "
				+ "timetable, worst {}s", iteration, what, bins, String.format("%.1f", sum / excesses.length),
				String.format("%.1f", 100.0 * late / excesses.length), String.format("%.1f", max));
	}
}
