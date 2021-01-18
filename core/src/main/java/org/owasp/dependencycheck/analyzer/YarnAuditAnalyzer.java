package org.owasp.dependencycheck.analyzer;

import com.google.common.base.Strings;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.concurrent.ThreadSafe;
import javax.json.Json;
import javax.json.JsonException;
import javax.json.JsonObject;
import javax.json.JsonReader;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.owasp.dependencycheck.Engine;
import org.owasp.dependencycheck.analyzer.exception.AnalysisException;
import org.owasp.dependencycheck.analyzer.exception.SearchException;
import org.owasp.dependencycheck.analyzer.exception.UnexpectedAnalysisException;
import org.owasp.dependencycheck.data.nodeaudit.Advisory;
import org.owasp.dependencycheck.data.nodeaudit.NpmPayloadBuilder;
import org.owasp.dependencycheck.dependency.Dependency;
import org.owasp.dependencycheck.exception.InitializationException;
import org.owasp.dependencycheck.utils.FileFilterBuilder;
import org.owasp.dependencycheck.utils.Settings;
import org.owasp.dependencycheck.utils.URLConnectionFailureException;
import org.owasp.dependencycheck.utils.processing.ProcessReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import us.springett.parsers.cpe.exceptions.CpeValidationException;

@ThreadSafe
public class YarnAuditAnalyzer extends AbstractNpmAnalyzer {

    /**
     * The logger.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(YarnAuditAnalyzer.class);

    /**
     * The file name to scan.
     */
    public static final String YARN_PACKAGE_LOCK = "yarn.lock";

    /**
     * Filter that detects files named "yarn.lock"
     */
    private static final FileFilter LOCK_FILE_FILTER = FileFilterBuilder.newInstance()
            .addFilenames(YARN_PACKAGE_LOCK).build();

    /**
     * An expected error from `yarn audit --offline --verbose --json` that will
     * be ignored.
     */
    private static final String EXPECTED_ERROR = "{\"type\":\"error\",\"data\":\"Can't make a request in "
            + "offline mode (\\\"https://registry.yarnpkg.com/-/npm/v1/security/audits\\\")\"}\n";

    @Override
    protected void analyzeDependency(Dependency dependency, Engine engine) throws AnalysisException {
        if (dependency.getDisplayFileName().equals(dependency.getFileName())) {
            engine.removeDependency(dependency);
        }
        final File packageLock = dependency.getActualFile();
        if (!packageLock.isFile() || packageLock.length() == 0 || !shouldProcess(packageLock)) {
            return;
        }
        final File packageJson = new File(packageLock.getParentFile(), "package.json");
        final List<Advisory> advisories;
        final Map<String, String> dependencyMap = new HashMap<>();
        advisories = analyzePackage(packageLock, packageJson, dependency, dependencyMap);
        try {
            processResults(advisories, engine, dependency, dependencyMap);
        } catch (CpeValidationException ex) {
            throw new UnexpectedAnalysisException(ex);
        }
    }

    @Override
    protected String getAnalyzerEnabledSettingKey() {
        return Settings.KEYS.ANALYZER_YARN_AUDIT_ENABLED;
    }

    @Override
    protected FileFilter getFileFilter() {
        return LOCK_FILE_FILTER;
    }

    @Override
    public String getName() {
        return "Yarn Audit Analyzer";
    }

    @Override
    public AnalysisPhase getAnalysisPhase() {
        return AnalysisPhase.FINDING_ANALYSIS;
    }

    /**
     * Initializes the analyzer once before any analysis is performed.
     *
     * @param engine a reference to the dependency-check engine
     * @throws InitializationException if there's an error during initialization
     */
    @Override
    protected void prepareFileTypeAnalyzer(Engine engine) throws InitializationException {
        super.prepareFileTypeAnalyzer(engine);
        if (!isEnabled()) {
            LOGGER.debug("{} Analyzer is disabled skipping yarn executable check", getName());
            return;
        }
        final List<String> args = new ArrayList<>();
        args.add("yarn");
        args.add("--help");
        final ProcessBuilder builder = new ProcessBuilder(args);
        LOGGER.debug("Launching: {}", args);
        try {
            Process process = builder.start();
            try (ProcessReader processReader = new ProcessReader(process)) {
                processReader.readAll();
                final int exitValue = process.waitFor();
                final int expectedExitValue = 0;
                final int yarnExecutableNotFoundExitValue = 127;
                switch (exitValue) {
                    case expectedExitValue:
                        LOGGER.debug("{} is enabled.", getName());
                        break;
                    case yarnExecutableNotFoundExitValue:
                        this.setEnabled(false);
                        LOGGER.warn("The {} has been disabled. Yarn executable was not found.", getName());
                    default:
                        this.setEnabled(false);
                        LOGGER.warn("The {} has been disabled. Yarn executable was not found.", getName());
                }
            }
        } catch (Exception ex) {
            this.setEnabled(false);
            LOGGER.debug("The {} has been disabled. Yarn executable was not found.", ex);
            LOGGER.warn("The {} has been disabled. Yarn executable was not found.", getName());
            throw new InitializationException("Unable to read yarn audit output.", ex);
        }
    }

    private JsonObject fetchYarnAuditJson(Dependency dependency, boolean skipDevDependencies) throws AnalysisException {
        File folder = dependency.getActualFile().getParentFile();
        if (!folder.isDirectory()) {
            throw new AnalysisException(String.format("%s should have been a directory.", folder.getAbsolutePath()));
        }
        try {
            final List<String> args = new ArrayList<>();

            args.add("yarn");
            args.add("audit");
            //offline audit is not supported - but the audit request is generated in the verbose output
            args.add("--offline");
            if (skipDevDependencies) {
                args.add("--groups");
                args.add("dependencies");
            }
            args.add("--json");
            args.add("--verbose");
            final ProcessBuilder builder = new ProcessBuilder(args);
            builder.directory(folder);
            LOGGER.debug("Launching: {}", args);
            Process process = builder.start();
            try (ProcessReader processReader = new ProcessReader(process)) {
                processReader.readAll();
                String errOutput = processReader.getError();

                if (!Strings.isNullOrEmpty(errOutput) && !EXPECTED_ERROR.equals(errOutput)) {
                    LOGGER.debug("Process Error Out: {}", errOutput);
                    LOGGER.debug("Process Out: {}", processReader.getOutput());
                }
                
                String verboseJson = Arrays.stream(processReader.getOutput().split("\n"))
                        .filter(line -> line.contains("Audit Request"))
                        .findFirst().get();
                String auditRequest;
                try (JsonReader reader = Json.createReader(IOUtils.toInputStream(verboseJson, StandardCharsets.UTF_8))) {
                    JsonObject jsonObject = reader.readObject();
                    auditRequest = jsonObject.getString("data");
                    //auditRequest = auditRequest.replace("Audit Request: ", "");
                    auditRequest = auditRequest.substring(15);
                }
                LOGGER.debug("Audit Request: {}", auditRequest);

                return Json.createReader(IOUtils.toInputStream(auditRequest, StandardCharsets.UTF_8)).readObject();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AnalysisException("Yarn audit process was interrupted.", ex);
            }
        } catch (IOException ioe) {
            throw new AnalysisException("yarn audit failure; this error can be ignored if you are not analyzing projects with a yarn lockfile.", ioe);
        }
    }

    /**
     * Analyzes the package and yarn lock files by extracting dependency
     * information, creating a payload to submit to the npm audit API,
     * submitting the payload, and returning the identified advisories.
     *
     * @param lockFile a reference to the package-lock.json
     * @param packageFile a reference to the package.json
     * @param dependency a reference to the dependency-object for the yarn.lock
     * @param dependencyMap a collection of module/version pairs; during
     * creation of the payload the dependency map is populated with the
     * module/version information.
     * @return a list of advisories
     * @throws AnalysisException thrown when there is an error creating or
     * submitting the npm audit API payload
     */
    private List<Advisory> analyzePackage(final File lockFile, final File packageFile,
            Dependency dependency, Map<String, String> dependencyMap)
            throws AnalysisException {
        try {
            Boolean skipDevDependencies = getSettings().getBoolean(Settings.KEYS.ANALYZER_NODE_AUDIT_SKIPDEV, false);
            // Retrieves the contents of package-lock.json from the Dependency
            final JsonObject lockJson = fetchYarnAuditJson(dependency, skipDevDependencies);
            // Retrieves the contents of package-lock.json from the Dependency
            final JsonReader packageReader = Json.createReader(FileUtils.openInputStream(packageFile));
            final JsonObject packageJson = packageReader.readObject();

            // Modify the payload to meet the NPM Audit API requirements
            final JsonObject payload = NpmPayloadBuilder.build(lockJson, packageJson, dependencyMap, skipDevDependencies);

            // Submits the package payload to the nsp check service
            return searcher.submitPackage(payload);

        } catch (URLConnectionFailureException e) {
            this.setEnabled(false);
            throw new AnalysisException("Failed to connect to the NPM Audit API (YarnAuditAnalyzer); the analyzer "
                    + "is being disabled and may result in false negatives.", e);
        } catch (IOException e) {
            LOGGER.debug("Error reading dependency or connecting to NPM Audit API", e);
            this.setEnabled(false);
            throw new AnalysisException("Failed to read results from the NPM Audit API (YarnAuditAnalyzer); "
                    + "the analyzer is being disabled and may result in false negatives.", e);
        } catch (JsonException e) {
            throw new AnalysisException(String.format("Failed to parse %s file from the NPM Audit API "
                    + "(YarnAuditAnalyzer).", lockFile.getPath()), e);
        } catch (SearchException ex) {
            LOGGER.error("YarnAuditAnalyzer failed on {}", dependency.getActualFilePath());
            throw ex;
        }
    }
}
