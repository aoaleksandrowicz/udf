package com.overops.udf.jira;

import java.net.URI;

import java.util.List;

import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

import com.google.common.base.Strings;
import com.google.gson.Gson;

import com.takipi.api.client.ApiClient;
import com.takipi.api.client.data.event.Action;
import com.takipi.api.client.data.view.SummarizedView;
import com.takipi.api.client.request.event.EventActionsRequest;
import com.takipi.api.client.request.event.EventDeleteRequest;
import com.takipi.api.client.request.event.EventInboxRequest;
import com.takipi.api.client.request.event.EventMarkResolvedRequest;
import com.takipi.api.client.request.event.EventsRequest;
import com.takipi.api.client.result.EmptyResult;
import com.takipi.api.client.result.event.EventActionsResult;
import com.takipi.api.client.result.event.EventResult;
import com.takipi.api.client.result.event.EventsResult;
import com.takipi.api.client.util.view.ViewUtil;
import com.takipi.api.core.url.UrlClient.Response;
import com.takipi.common.util.CollectionUtil;
import com.takipi.udf.ContextArgs;
import com.takipi.udf.input.Input;

import com.atlassian.jira.rest.client.JiraRestClient;
import com.atlassian.jira.rest.client.JiraRestClientFactory;
import com.atlassian.jira.rest.client.domain.Issue;
import com.atlassian.jira.rest.client.domain.Transition;
import com.atlassian.jira.rest.client.domain.input.TransitionInput;
import com.atlassian.jira.rest.client.internal.async.AsynchronousJiraRestClientFactory;

public class JiraIntegrationFunction {
	public static String validateInput(String rawInput) {
		return getJiraIntegrationInput(rawInput).toString();
	}

	public static void execute(String rawContextArgs, String rawInput) {
		JiraIntegrationInput input = getJiraIntegrationInput(rawInput);

		ContextArgs args = (new Gson()).fromJson(rawContextArgs, ContextArgs.class);

		System.out.println("execute context: " + rawContextArgs);

		if (!args.validate())
			throw new IllegalArgumentException("Bad context args: " + rawContextArgs);

		if (!args.viewValidate())
			return;

		ApiClient apiClient = args.apiClient();

		// get all events that have occurred in the last {timespan} days
		DateTime to = DateTime.now();
		DateTime from = to.minusDays(input.timespan);

		DateTimeFormatter fmt = ISODateTimeFormat.dateTime().withZoneUTC();

		EventsRequest eventsRequest = EventsRequest.newBuilder().setServiceId(args.serviceId).setViewId(args.viewId)
				.setFrom(from.toString(fmt)).setTo(to.toString(fmt)).build();

		// GET events
		Response<EventsResult> eventsResponse = apiClient.get(eventsRequest);

		// validate API response
		if (eventsResponse.isBadResponse())
			throw new IllegalStateException("Failed getting events.");

		// get data
		EventsResult eventsResult = eventsResponse.data;

		// check for events
		if (CollectionUtil.safeIsEmpty(eventsResult.events)) {
			System.out.println("Found no events from the last " + input.timespan + " minutes.");
			return;
		}

		// get list of events
		List<EventResult> events = eventsResult.events;

		// Construct the JRJC client
		System.out.println(String.format("Logging in to %s with username '%s'", input.jiraURL, input.jiraUsername));
		JiraRestClientFactory factory = new AsynchronousJiraRestClientFactory();
		URI uri;

		try {

			uri = new URI(input.jiraURL);
			JiraRestClient client = factory.createWithBasicHttpAuthentication(uri, input.jiraUsername, input.jiraPassword);

			// for each event with a Jira issue URL
			for (EventResult event : events) {
				if (event.jira_issue_url != null) {

					// get Jira issue ID from OO event's Jira issue URL
					String issueId = getJiraIssueId(event.jira_issue_url);

					// get issue from Jira
					Issue issue = client.getIssueClient().getIssue(issueId).claim();

					// get Jira issue status
					String issueStatus = issue.getStatus().getName();

					// Jira updated date
					DateTime jiraDate = issue.getUpdateDate();

					// OverOps updated date
					DateTime overopsDate = getUpdateDate(args.serviceId, event.id, apiClient);

					// TODO open a jira ticket for an API that lets me query for Jira / open / hidden ... to
					// not time sync
					// TODO resurfaced = deployment version needs to change <---

					// TODO ensure Jira statuses are unique
					// TODO make sure null, empty string, undefined all work

					boolean isResolved = event.labels.contains("Resolved"); // is resolved in OverOps?
					boolean isHidden = event.labels.contains("Archive"); // is hidden in OverOps?
					boolean isInbox = event.labels.contains("Inbox"); // is in inbox in OverOps?
					boolean isResurfaced = event.labels.contains("Resurfaced"); // is resurfaced in OverOps?

					boolean isResolvedStatus = issueStatus.equals(input.resolvedStatus); // is resolved in Jira?
					boolean isHiddenStatus = issueStatus.equals(input.hiddenStatus); // is hidden in Jira?
					boolean isInboxStatus = issueStatus.equals(input.inboxStatus); // is in inbox in Jira?
					boolean isResurfacedStatus = issueStatus.equals(input.resurfacedStatus); // is resurfaced in Jira?

					// compare dates
					if (jiraDate.isAfter(overopsDate)) {
						// Sync Jira to OverOps

						if (isResolvedStatus) {
							// 1. resolve in Jira → resolve in OO

							// if not resolved, resolve in OverOps
							if (!isResolved) {
								EventMarkResolvedRequest resolvedRequest = EventMarkResolvedRequest.newBuilder()
										.setServiceId(args.serviceId).setEventId(event.id).build();

								Response<EmptyResult> resolvedResponse = apiClient.post(resolvedRequest);

								if (resolvedResponse.isBadResponse())
									throw new IllegalStateException("Failed resolving event " + event.id);
							}

						} else if (isHiddenStatus) {
							// 2. hide in Jira → hide in OO

							// if not hidden, hide (delete/trash/archive) in OverOps
							if (!isHidden) {
								EventDeleteRequest hideRequest = EventDeleteRequest.newBuilder().setServiceId(args.serviceId)
										.setEventId(event.id).build();

								Response<EmptyResult> hideResponse = apiClient.post(hideRequest);

								if (hideResponse.isBadResponse())
									throw new IllegalStateException("Failed hiding event " + event.id);
							}

						} else {
							// 3. anything else in Jira → move to inbox in OO

							// if not in inbox, move to inbox in OverOps
							if (!isInbox) {
								EventInboxRequest inboxRequest = EventInboxRequest.newBuilder().setServiceId(args.serviceId)
										.setEventId(event.id).build();

								Response<EmptyResult> resolvedResponse = apiClient.post(inboxRequest);

								if (resolvedResponse.isBadResponse())
									throw new IllegalStateException("Failed moving event to inbox " + event.id);
							}

						}

					} else {
						// Sync OverOps to Jira
						System.out.println("Sync OverOps → Jira");

						// get possible status transitions
						Iterable<Transition> transitions = client.getIssueClient().getTransitions(issue).claim();

						if (isResolved) {
							// 1. resolve in OO → resolve in Jira

							// if not resolved, resolve in Jira
							if (!isResolvedStatus) {
								for (Transition transition : transitions) {
									if (transition.getName().equals(input.resolvedStatus)) {
										TransitionInput transitionInput = new TransitionInput(transition.getId());
										client.getIssueClient().transition(issue, transitionInput).claim();
										break;
									}
								}
							}

						} else if (isHidden) {
							// 2. hide in OO → hide in Jira

							// if not hidden, hide in Jira
							if (!isHiddenStatus) {
								for (Transition transition : transitions) {
									if (transition.getName().equals(input.hiddenStatus)) {
										TransitionInput transitionInput = new TransitionInput(transition.getId());
										client.getIssueClient().transition(issue, transitionInput).claim();
										break;
									}
								}
							}

						} else if (isResurfaced) {
							// 3. resurfaced in OO → resurfaced in Jira

							if (!isResurfacedStatus) {
								for (Transition transition : transitions) {
									if (transition.getName().equals(input.resurfacedStatus)) {
										TransitionInput transitionInput = new TransitionInput(transition.getId());
										client.getIssueClient().transition(issue, transitionInput).claim();
										break;
									}
								}
							}

						} else {
							// 4. anything else, mark "in Inbox" in Jira
							for (Transition transition : transitions) {
								if (transition.getName().equals(input.inboxStatus)) {
									TransitionInput transitionInput = new TransitionInput(transition.getId());
									client.getIssueClient().transition(issue, transitionInput).claim();
									break;
								}
							}
						}

					}

				}

			}

		} catch (Exception e) {
			System.err.println("Caught exception. Check settings and try again.");
			e.printStackTrace();
			System.exit(1);
		}

		System.out.println("Sync complete.");
		System.exit(0);
	}

	// get Jira issue ID from Jira issue URL
	private static String getJiraIssueId(String jiraURL) {
		int index = jiraURL.lastIndexOf("/")+1;
		return jiraURL.substring(index);
	}

	// get overops update date from last event action
	private static DateTime getUpdateDate(String serviceId, String eventId, ApiClient apiClient) {

		EventActionsRequest eventActions = EventActionsRequest.newBuilder()
			.setServiceId(serviceId).setEventId(eventId).build();

		// GET event actions
		Response<EventActionsResult> eventActionsResponse = apiClient.get(eventActions);

		// validate API response
		if (eventActionsResponse.isBadResponse())
			throw new IllegalStateException("Failed getting event actions.");

		// extract data
		EventActionsResult eventActionsResult = eventActionsResponse.data;

		// get list of event actions
		List<Action> actions = eventActionsResult.event_actions;

		DateTime overopsDate = null;

		// get most recent event action timestamp
		if (actions != null && actions.size() > 0) {
			Action a = actions.get(0);
			overopsDate = new DateTime(a.timestamp);
		} else {
			// if null, use now
			overopsDate = new DateTime();
		}

		return overopsDate;
	}

	private static JiraIntegrationInput getJiraIntegrationInput(String rawInput) {
		// params cannot be empty
		if (Strings.isNullOrEmpty(rawInput))
			throw new IllegalArgumentException("Input is empty");

		JiraIntegrationInput input;

		// parse params
		try {
			input = JiraIntegrationInput.of(rawInput);
		} catch (Exception e) {
			throw new IllegalArgumentException(e.getMessage(), e);
		}

		// validate timespan
		if (input.timespan <= 0)
			throw new IllegalArgumentException("'timespan' must be at least 1 day");

		if (input.jiraURL == null || input.jiraURL.isEmpty())
			throw new IllegalArgumentException("'jiraURL' is required");

		if (input.jiraUsername == null || input.jiraUsername.isEmpty())
			throw new IllegalArgumentException("'jiraUsername' is required");

		if (input.jiraPassword == null || input.jiraPassword.isEmpty())
			throw new IllegalArgumentException("'jiraPassword' is required");


		return input;
	}

	static class JiraIntegrationInput extends Input {
		public int timespan; // in days

		public String jiraURL;
		public String jiraUsername;
		public String jiraPassword;

		public String inboxStatus;
		public String resolvedStatus;
		public String hiddenStatus;
		public String resurfacedStatus;

		// TODO
		// public boolean handleSimilarEvents;

		private JiraIntegrationInput(String raw) {
			super(raw);
		}

		@Override
		public String toString() {
			StringBuilder builder = new StringBuilder();

			builder.append("Sync Jira");

			return builder.toString();
		}

		static JiraIntegrationInput of(String raw) {
			return new JiraIntegrationInput(raw);
		}
	}

	// for testing
	public static void main(String[] args) {
		if ((args == null) || (args.length < 6))
			throw new IllegalArgumentException("java JiraIntegrationFunction API_URL API_KEY SERVICE_ID JIRA_URL JIRA_USER JIRA_PASS");

		ContextArgs contextArgs = new ContextArgs();

		contextArgs.apiHost = args[0];
		contextArgs.apiKey = args[1];
		contextArgs.serviceId = args[2];

		SummarizedView view = ViewUtil.getServiceViewByName(contextArgs.apiClient(), contextArgs.serviceId, "All Events");

		contextArgs.viewId = view.id;

		System.out.println("current time: " + DateTime.now());
		System.out.println("view id: " + view.id);

		// some test values
		String[] sampleValues = new String[] { 
			"timespan=5",
			"jiraURL=" + args[3],
			"jiraUsername=" + args[4],
			"jiraPassword=" + args[5],
			"resolvedStatus=Resolved",
			"hiddenStatus=Closed",
			"inboxStatus=To Do",
			"resurfacedStatus=Reopened"
		};

		String rawContextArgs = new Gson().toJson(contextArgs);
		JiraIntegrationFunction.execute(rawContextArgs, String.join("\n", sampleValues));
	}
}
