import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.config.properties.APKeys
import com.atlassian.jira.user.ApplicationUser

import com.atlassian.sal.api.net.Request
import com.atlassian.sal.api.net.Response
import com.atlassian.sal.api.net.ResponseException
import com.atlassian.sal.api.net.ReturningResponseHandler
import com.atlassian.sal.api.net.TrustedRequest
import com.atlassian.sal.api.net.TrustedRequestFactory

import groovy.json.JsonSlurper
import groovy.json.JsonOutput // For potentially building payloads in other scripts
import groovyx.net.http.ContentType // Useful for ContentType.JSON
import groovyx.net.http.URIBuilder

import java.net.HttpURLConnection // For HTTP status codes

// --- Configuration ---
// !!! IMPORTANT: Replace "PROJECT-123" with the actual issue key you want to fetch !!!
final String ISSUE_KEY_TO_FETCH = "PROJECT-123"
final String JIRA_API_V2_PATH = "/rest/api/2"
// --- End Configuration ---

// Get current logged-in user
ApplicationUser currentUser = ComponentAccessor.jiraAuthenticationContext.loggedInUser
if (!currentUser) {
    log.error("No logged-in user found. This script requires a user context for Trusted Applications.")
    return null // Or throw an exception
}

// Get JIRA Base URL
String baseUrl = ComponentAccessor.applicationProperties.getString(APKeys.JIRA_BASEURL)
if (!baseUrl) {
    log.error("JIRA Base URL (APKeys.JIRA_BASEURL) is not configured.")
    return null // Or throw an exception
}

// Get TrustedRequestFactory OSGi service
TrustedRequestFactory trustedRequestFactory = ComponentAccessor.getOSGiComponentInstanceOfType(TrustedRequestFactory)
if (!trustedRequestFactory) {
    log.error("Could not retrieve TrustedRequestFactory. Ensure the 'Trusted Applications' plugin is enabled and functioning.")
    return null // Or throw an exception
}

// Construct the target URL
// Example: http://your-jira.com/rest/api/2/issue/PROJECT-123
String endpointPath = "${JIRA_API_V2_PATH}/issue/${ISSUE_KEY_TO_FETCH}"
String targetUrl = baseUrl + endpointPath

log.info("Attempting to fetch issue data from: ${targetUrl} as user: ${currentUser.name}")

try {
    // Create a trusted GET request
    TrustedRequest request = trustedRequestFactory.createTrustedRequest(Request.MethodType.GET, targetUrl)

    // Add trusted token authentication.
    // The first parameter is the application ID/name as configured in Trusted Applications.
    // For requests to the *same* JIRA instance, using the host of the baseUrl is common.
    request.addTrustedTokenAuthentication(new URIBuilder(baseUrl).host, currentUser.name)

    // Set headers
    // For GET, 'Accept' tells the server what kind of response we want.
    request.addHeader("Accept", ContentType.JSON.toString())
    // 'X-Atlassian-Token: no-check' bypasses XSRF protection.
    // It's primarily for state-changing requests (POST, PUT, DELETE) but harmless for GET.
    request.addHeader("X-Atlassian-Token", "no-check")

    // If you were doing a POST or PUT, you would set the request body here:
    // def payload = JsonOutput.toJson([someKey: 'someValue'])
    // request.setRequestBody(payload)
    // request.addHeader("Content-Type", ContentType.JSON.toString()) // Then Content-Type is essential

    // Execute the request and handle the response
    Object issueData = request.executeAndReturn(new ReturningResponseHandler<Response, Object>() {
        @Override
        Object handle(Response response) throws ResponseException {
            if (response.statusCode == HttpURLConnection.HTTP_OK) {
                try {
                    String responseBody = response.responseBodyAsString
                    log.debug("Successful response from API. Status: ${response.statusCode}, Body: ${responseBody}")
                    def jsonResponse = new JsonSlurper().parseText(responseBody)
                    log.info("Successfully fetched and parsed issue data for: ${ISSUE_KEY_TO_FETCH}")
                    return jsonResponse
                } catch (Exception e) {
                    log.error("Error parsing JSON response for ${ISSUE_KEY_TO_FETCH}: ${e.message}", e)
                    throw new ResponseException("Failed to parse JSON response: ${e.message}")
                }
            } else {
                String errorBody = response.responseBodyAsString
                log.error("Received an error from API. Status: ${response.statusCode}, URL: ${targetUrl}, User: ${currentUser.name}, Response: ${errorBody}")
                // Consider throwing a more specific exception or returning a custom error object
                throw new ResponseException("API request failed with status ${response.statusCode}. Response: ${errorBody}")
            }
        }
    })

    // 'issueData' now holds the parsed JSON response or will be null/exception thrown if an error occurred
    if (issueData) {
        log.info("Example: Issue Summary: ${issueData.fields?.summary}")
        // You can now work with the issueData map
        // e.g., println issueData.key
        // println issueData.fields.summary
    }

    return issueData // This will be the output in ScriptRunner console

} catch (ResponseException re) {
    log.error("ResponseException during API call to ${targetUrl}: ${re.getMessage()}", re)
    // Depending on context, you might want to re-throw or return a specific error indicator
    return "Error: API call failed with ResponseException. Check logs for details."
} catch (Exception e) {
    log.error("An unexpected error occurred while making the API call to ${targetUrl}: ${e.getMessage()}", e)
    return "Error: An unexpected error occurred. Check logs for details."
}
