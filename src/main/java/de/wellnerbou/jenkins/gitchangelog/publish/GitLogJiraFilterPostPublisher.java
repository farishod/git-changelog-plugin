package de.wellnerbou.jenkins.gitchangelog.publish;

import de.wellnerbou.gitchangelog.app.AppArgs;
import de.wellnerbou.gitchangelog.app.GitChangelog;
import de.wellnerbou.gitchangelog.model.Changelog;
import de.wellnerbou.gitchangelog.processors.jira.JiraFilterChangelogProcessor;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.remoting.Callable;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import jenkins.security.MasterToSlaveCallable;
import org.jenkinsci.remoting.RoleChecker;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;

public class GitLogJiraFilterPostPublisher extends Publisher {

	public final String jirabaseurl;
	public final String jiraprefix;
	public final String outputfile;
	public final String toRev;
	public final String fromRev;

	@DataBoundConstructor
	public GitLogJiraFilterPostPublisher(final String jirabaseurl, final String jiraprefix, final String outputfile, final String fromRev, final String toRev) {
		this.jirabaseurl = jirabaseurl;
		this.jiraprefix = jiraprefix;
		this.outputfile = outputfile;
		this.fromRev = fromRev;
		this.toRev = toRev;
	}

	@Override
	public BuildStepMonitor getRequiredMonitorService() {
		return BuildStepMonitor.NONE;
	}

	@Override
	public boolean perform(final AbstractBuild<?, ?> build, final Launcher launcher, final BuildListener listener) throws InterruptedException, IOException {
		final AppArgs appArgs = createAppArgs(build, build.getEnvironment(listener));
		final PrintStream printStream = decideForPrintStream(listener);

		Callable<String, IOException> task = new MasterToSlaveCallable<String, IOException>() {
			public String call() throws IOException {
				final GitChangelog gitChangelog = new GitChangelog(appArgs, printStream);
				final Changelog changelog = gitChangelog.changelog();
				return gitChangelog.print(changelog);
			}
		};
		build.getWorkspace().act(task);
		closePrintStreamIfWrittenToFile(printStream);

		return true;
	}

	private void closePrintStreamIfWrittenToFile(final PrintStream printStream) {
		if (shouldWriteToFile()) {
			printStream.close();
		}
	}

	private boolean shouldWriteToFile() {
		return outputfile != null && outputfile.length() > 0;
	}

	private PrintStream decideForPrintStream(final BuildListener listener) throws FileNotFoundException {
		PrintStream printStream = listener.getLogger();
		if (shouldWriteToFile()) {
			final File file = new File(outputfile);
			listener.getLogger().println("Saving git changelog output to file " + file.getAbsolutePath() + ".");
			printStream = new PrintStream(file);
		}
		return printStream;
	}

	private AppArgs createAppArgs(final AbstractBuild<?, ?> build, final EnvVars env) throws AbortException {
		if (build.getWorkspace() == null) {
			throw new AbortException("no workspace for " + build);
		}
		AppArgs appArgs = new AppArgs();
		final JiraFilterChangelogProcessor jiraFilterChangelogProcessor = createChangelogProcessor(env);
		appArgs.setChangelogProcessor(jiraFilterChangelogProcessor);
		appArgs.setRepo(build.getWorkspace().getRemote());

		if (fromRev != null && fromRev.length() > 0) {
			appArgs.setFromRev(env.expand(fromRev));
		}
		if (toRev != null && toRev.length() > 0) {
			appArgs.setToRev(env.expand(toRev));
		}
		return appArgs;
	}

	private void logLocation(final BuildListener listener, final FilePath workspace) {
		listener.getLogger().println("I am on machine "+System.getenv("HOSTNAME"));
		listener.getLogger().println("Using workspace " + workspace.getRemote() + " as git repository.");
		final File file1 = new File(workspace.getRemote());
		listener.getLogger().println("File " + file1.getAbsolutePath() + " exists: " + file1.exists());
	}

	private JiraFilterChangelogProcessor createChangelogProcessor(final EnvVars env) {
		final JiraFilterChangelogProcessor jiraFilterChangelogProcessor = new JiraFilterChangelogProcessor();
		jiraFilterChangelogProcessor.setJiraBaseUrl(env.expand(jirabaseurl));
		jiraFilterChangelogProcessor.setJiraProjectPrefixes(env.expand(jiraprefix));
		return jiraFilterChangelogProcessor;
	}

	@Extension
	public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {

		@Override
		public String getDisplayName() {
			return "Publish JIRA Filter";
		}

		@Override
		public boolean isApplicable(Class<? extends AbstractProject> jobType) {
			return true;
		}
	}
}
