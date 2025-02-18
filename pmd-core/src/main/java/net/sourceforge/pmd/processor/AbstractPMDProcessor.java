/**
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.processor;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.exception.ContextedRuntimeException;

import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.Report;
import net.sourceforge.pmd.Rule;
import net.sourceforge.pmd.RuleContext;
import net.sourceforge.pmd.RuleSetFactory;
import net.sourceforge.pmd.RuleSets;
import net.sourceforge.pmd.RulesetsFactoryUtils;
import net.sourceforge.pmd.SourceCodeProcessor;
import net.sourceforge.pmd.annotation.InternalApi;
import net.sourceforge.pmd.benchmark.TimeTracker;
import net.sourceforge.pmd.benchmark.TimedOperation;
import net.sourceforge.pmd.benchmark.TimedOperationCategory;
import net.sourceforge.pmd.renderers.Renderer;
import net.sourceforge.pmd.util.datasource.DataSource;

/**
 * @author Romain Pelisse &lt;belaran@gmail.com&gt;
 *
 * @deprecated Is internal API
 */
@Deprecated
@InternalApi
public abstract class AbstractPMDProcessor {

    private static final Logger LOG = Logger.getLogger(AbstractPMDProcessor.class.getName());

    protected final PMDConfiguration configuration;

    public AbstractPMDProcessor(PMDConfiguration configuration) {
        this.configuration = configuration;
    }

    public void renderReports(final List<Renderer> renderers, final Report report) {

        try (TimedOperation to = TimeTracker.startOperation(TimedOperationCategory.REPORTING)) {
            for (Renderer r : renderers) {
                r.renderFileReport(report);
            }
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
    }

    /**
     *
     * @deprecated this method will be removed. It was once used to determine a short filename
     * for the file being analyzed, so that shortnames can be reported. But the logic has
     * been moved to the renderers.
     */
    @Deprecated
    protected String filenameFrom(DataSource dataSource) {
        return dataSource.getNiceFileName(configuration.isReportShortNames(), configuration.getInputPaths());
    }

    /**
     * Create instances for each rule defined in the ruleset(s) in the
     * configuration. Please note, that the returned instances <strong>must
     * not</strong> be used by different threads. Each thread must create its
     * own copy of the rules.
     *
     * @param factory The factory used to create the configured rule sets
     * @param report The base report on which to report any configuration errors
     * @return the rules within a rulesets
     *
     * @deprecated Rulesets are created upstream of the PMDProcessor now.
     */
    @Deprecated
    protected RuleSets createRuleSets(RuleSetFactory factory, Report report) {
        final RuleSets rs = RulesetsFactoryUtils.getRuleSets(configuration.getRuleSets(), factory);
        reportBrokenRules(report, rs);
        return rs;
    }

    @Deprecated
    public static void reportBrokenRules(Report report, RuleSets rs) {
        final Set<Rule> brokenRules = removeBrokenRules(rs);
        for (final Rule rule : brokenRules) {
            report.addConfigError(new Report.ConfigurationError(rule, rule.dysfunctionReason()));
        }
    }

    /**
     * Remove and return the misconfigured rules from the rulesets and log them
     * for good measure.
     *
     * @param ruleSets RuleSets to prune of broken rules.
     * @return Set<Rule>
     */
    private static Set<Rule> removeBrokenRules(final RuleSets ruleSets) {
        final Set<Rule> brokenRules = new HashSet<>();
        ruleSets.removeDysfunctionalRules(brokenRules);

        for (final Rule rule : brokenRules) {
            if (LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING,
                        "Removed misconfigured rule: " + rule.getName() + "  cause: " + rule.dysfunctionReason());
            }
        }

        return brokenRules;
    }

    @Deprecated
    public void processFiles(RuleSetFactory ruleSetFactory, List<DataSource> files, RuleContext ctx,
                             List<Renderer> renderers) {
        RuleSets rs = createRuleSets(ruleSetFactory, ctx.getReport());
        processFiles(rs, files, ctx, renderers);
    }

    @SuppressWarnings("PMD.CloseResource")
    // the data sources must only be closed after the threads are finished
    // this is done manually without a try-with-resources
    public void processFiles(RuleSets rulesets, List<DataSource> files, RuleContext ctx, List<Renderer> renderers) {
        try {
            reportBrokenRules(ctx.getReport(), rulesets);

            // render base report first - general errors
            renderReports(renderers, ctx.getReport());

            configuration.getAnalysisCache().checkValidity(rulesets, configuration.getClassLoader());
            final SourceCodeProcessor processor = new SourceCodeProcessor(configuration);

            for (final DataSource dataSource : files) {
                // this is the real, canonical and absolute filename (not shortened)
                String realFileName = dataSource.getNiceFileName(false, null);

                runAnalysis(new PmdRunnable(dataSource, realFileName, renderers, ctx, rulesets, processor));
            }

            // then add analysis results per file
            collectReports(renderers);
        } catch (RuntimeException e) {
            throw new ContextedRuntimeException(e).addContextValue("filename", String.valueOf(ctx.getSourceCodeFile()));
        } finally {
            // in case we analyzed files within Zip Files/Jars, we need to close them after
            // the analysis is finished
            for (DataSource dataSource : files) {
                IOUtils.closeQuietly(dataSource);
            }
        }
    }

    protected abstract void runAnalysis(PmdRunnable runnable);

    protected abstract void collectReports(List<Renderer> renderers);
}
