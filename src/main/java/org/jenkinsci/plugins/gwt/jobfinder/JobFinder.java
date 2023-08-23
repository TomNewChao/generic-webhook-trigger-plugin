package org.jenkinsci.plugins.gwt.jobfinder;

import static com.google.common.base.Strings.isNullOrEmpty;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.security.MessageDigest.isEqual;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import hudson.model.BuildAuthorizationToken;
import hudson.model.Item;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.ParameterizedJobMixIn;
import jenkins.model.ParameterizedJobMixIn.ParameterizedJob;
import org.jenkinsci.plugins.gwt.FoundJob;
import org.jenkinsci.plugins.gwt.GenericTrigger;
import org.jenkinsci.plugins.gwt.global.JobFinderConfig;
import org.jenkinsci.plugins.gwt.resolvers.JsonFlattener;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

public final class JobFinder {

  private static Logger LOG = Logger.getLogger(JobFinder.class.getName());

  private JobFinder() {}

  private static JobFinderImpersonater jobFinderImpersonater = new JobFinderImpersonater();

  @VisibleForTesting
  static void setJobFinderImpersonater(final JobFinderImpersonater jobFinderImpersonater) {
    JobFinder.jobFinderImpersonater = jobFinderImpersonater;
  }

  /** This is the factory to choice findJobWithFullName or findAllJobsWithTrigger. */
  public static List<FoundJob> foundJobs(final String givenToken, final String postContent) {
    List<FoundJob> foundJobs;
    if (JobFinderConfig.get().isEnabled()) {
      foundJobs = findJobWithFullName(givenToken, postContent);
      if (foundJobs.isEmpty()) {
        foundJobs = findAllJobsWithTrigger(givenToken);
      }
    } else {
      foundJobs = findAllJobsWithTrigger(givenToken);
    }
    return foundJobs;
  }

  @VisibleForTesting
  public static JsonObject getJsonObject(String postContent) {
    Gson GSON = new GsonBuilder().serializeNulls().create();
    return GSON.fromJson(postContent, JsonObject.class);
  }

  private static List<FoundJob> findJobWithFullName(
      final String givenToken, final String postContent) {
    final List<FoundJob> found = new ArrayList<>();
    if (postContent == null || postContent.isEmpty()) {
      return found;
    }
    String regexFilter = "";
    String postContentKey = postContent.getClass().getName();
    JsonFlattener flattenJson = new JsonFlattener();
    JsonObject resolved = getJsonObject(postContent);
    final Map<String, String> postMap =
        flattenJson.flattenJson(postContentKey, regexFilter, resolved);
    final List<String> fullNameList = JobFinderConfig.get().getFullNameList(postMap);
    final boolean impersonate = !isNullOrEmpty(givenToken);
    final List<ParameterizedJobMixIn.ParameterizedJob> candidateProjects =
        JobFinderImpersonater.getAllParameterizedJobsByFullNameList(impersonate, fullNameList);
    for (final ParameterizedJobMixIn.ParameterizedJob candidateJob : candidateProjects) {
      final GenericTrigger genericTriggerOpt = findGenericTrigger(candidateJob.getTriggers());
      if (genericTriggerOpt != null) {
        final FoundJob foundJob = new FoundJob(candidateJob.getFullName(), genericTriggerOpt);
        found.add(foundJob);
      }
    }
    return found;
  }

  public static List<FoundJob> findAllJobsWithTrigger(final String givenToken) {

    final List<FoundJob> found = new ArrayList<>();

    final boolean impersonate = !isNullOrEmpty(givenToken);
    final List<ParameterizedJob> candidateProjects =
        jobFinderImpersonater.getAllParameterizedJobs(impersonate);
    for (final ParameterizedJob candidateJob : candidateProjects) {
      final GenericTrigger genericTriggerOpt = findGenericTrigger(candidateJob.getTriggers());
      if (genericTriggerOpt != null) {
        final String configuredToken =
            determineTokenValue(
                candidateJob,
                genericTriggerOpt.getToken(),
                genericTriggerOpt.getTokenCredentialId());
        final boolean authenticationTokenMatches =
            authenticationTokenMatches(givenToken, candidateJob.getAuthToken(), configuredToken);
        if (authenticationTokenMatches) {
          final FoundJob foundJob = new FoundJob(candidateJob.getFullName(), genericTriggerOpt);
          found.add(foundJob);
        }
      }
    }

    return found;
  }

  private static String determineTokenValue(
      final Item item, final String token, final String tokenCredentialsId) {
    if (isNullOrEmpty(tokenCredentialsId)) {
      LOG.log(Level.FINE, "Found no credential configured in " + item.getFullDisplayName());
      return token;
    }
    if (!isNullOrEmpty(tokenCredentialsId) && !isNullOrEmpty(token)) {
      LOG.log(
          Level.WARNING,
          "The job "
              + item.getFullDisplayName()
              + " is configured with both static token and token from credential "
              + tokenCredentialsId
              + ".");
    }
    final Optional<StringCredentials> credentialsOpt =
        org.jenkinsci.plugins.gwt.global.CredentialsHelper.findCredentials(
            tokenCredentialsId, item);
    if (credentialsOpt.isPresent()) {
      LOG.log(
          Level.FINE,
          "Found credential from "
              + tokenCredentialsId
              + " configured in "
              + item.getFullDisplayName());
      return credentialsOpt.get().getSecret().getPlainText();
    }
    LOG.log(
        Level.SEVERE,
        "Cannot find credential ("
            + tokenCredentialsId
            + ") configured in "
            + item.getFullDisplayName());
    return token;
  }

  private static boolean authenticationTokenMatches(
      final String givenToken,
      @SuppressWarnings("deprecation") final BuildAuthorizationToken authToken,
      final String genericToken) {
    final boolean noTokenGiven = isNullOrEmpty(givenToken);
    final boolean noKindOfTokenConfigured =
        isNullOrEmpty(genericToken) && !jobHasAuthToken(authToken);
    final boolean genericTokenNotConfigured = isNullOrEmpty(genericToken);
    final boolean authTokenNotConfigured = !jobHasAuthToken(authToken);
    return genericTokenNotConfigured && authenticationTokenMatches(authToken, givenToken)
        || authTokenNotConfigured && authenticationTokenMatchesGeneric(genericToken, givenToken)
        || noTokenGiven && noKindOfTokenConfigured;
  }

  /** This is the token configured in this plugin. */
  private static boolean authenticationTokenMatchesGeneric(
      final String token, final String givenToken) {
    final boolean jobHasAuthToken = !isNullOrEmpty(token);
    final boolean authTokenWasGiven = !isNullOrEmpty(givenToken);
    if (jobHasAuthToken && authTokenWasGiven) {
      return isEqual(token.getBytes(UTF_8), givenToken.getBytes(UTF_8));
    }
    if (!jobHasAuthToken && !authTokenWasGiven) {
      return true;
    }
    return false;
  }

  /** This is the token configured in the job. A feature found in Jenkins core. */
  @SuppressWarnings("deprecation")
  private static boolean authenticationTokenMatches(
      final hudson.model.BuildAuthorizationToken authToken, final String givenToken) {

    final boolean jobHasAuthToken = jobHasAuthToken(authToken);
    final boolean authTokenWasGiven = !isNullOrEmpty(givenToken);
    if (jobHasAuthToken && authTokenWasGiven) {
      return isEqual(authToken.getToken().getBytes(UTF_8), givenToken.getBytes(UTF_8));
    }
    if (!jobHasAuthToken && !authTokenWasGiven) {
      return true;
    }
    return false;
  }

  @SuppressWarnings("deprecation")
  private static boolean jobHasAuthToken(final hudson.model.BuildAuthorizationToken authToken) {
    return authToken != null && !isNullOrEmpty(authToken.getToken());
  }

  private static GenericTrigger findGenericTrigger(
      final Map<TriggerDescriptor, Trigger<?>> triggers) {
    if (triggers == null) {
      return null;
    }
    for (final Trigger<?> candidate : triggers.values()) {
      if (candidate instanceof GenericTrigger) {
        return (GenericTrigger) candidate;
      }
    }
    return null;
  }
}
