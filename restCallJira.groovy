import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.config.properties.APKeys
import com.atlassian.sal.api.net.Response
import com.atlassian.sal.api.net.ResponseException
import com.atlassian.sal.api.net.ReturningResponseHandler
import com.atlassian.sal.api.net.TrustedRequest
import com.atlassian.sal.api.net.TrustedRequestFactory
import com.atlassian.sal.api.net.Request
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import groovyx.net.http.ContentType
import groovyx.net.http.URIBuilder
import java.net.URL;
import java.nio.file.Path;


def currentUser = ComponentAccessor.jiraAuthenticationContext.loggedInUser
def baseUrl = ComponentAccessor.applicationProperties.getString(APKeys.JIRA_BASEURL)
def trustedRequestFactory = ComponentAccessor.getOSGiComponentInstanceOfType(TrustedRequestFactory)
 
//def payload = new JsonBuilder([payloadField: 'payload value']).toString()
def endPointPath = '/rest/api/2/issue/<issue key>'
def url = baseUrl + endPointPath
 
def request = trustedRequestFactory.createTrustedRequest(Request.MethodType.GET, url) as TrustedRequest
request.addTrustedTokenAuthentication(new URIBuilder(baseUrl).host, currentUser.name)
request.addHeader("Content-Type", ContentType.JSON.toString())
request.addHeader("X-Atlassian-Token", 'no-check')
//request.setRequestBody(payload)
 
def response = request.executeAndReturn(new ReturningResponseHandler<Response, Object>() {
    Object handle(Response response) throws ResponseException {
        if (response.statusCode != HttpURLConnection.HTTP_OK) {
            log.error "Received an error while posting to the rest api. StatusCode=$response.statusCode. Response Body: $response.responseBodyAsString"
            return null
        } else {
            def jsonResp = new JsonSlurper().parseText(response.responseBodyAsString)
            log.info "REST API reports success: $jsonResp"
 
            return jsonResp
             }
        }
    })
