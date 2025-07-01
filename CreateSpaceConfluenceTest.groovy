import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.applinks.api.ApplicationLinkService
import com.atlassian.applinks.api.application.confluence.Confluence
import com.atlassian.httpclient.api.Request
import com.atlassian.httpclient.api.entity.ContentTypes
import groovy.json.JsonBuilder

// ── 1. Grab the issue & custom-fields ───────────────────────────────────
def issue       = issue
def cfSummary   = ComponentAccessor.customFieldManager.getCustomFieldObjectByName("Summary of the Space")
def cfPurpose   = ComponentAccessor.customFieldManager.getCustomFieldObjectByName("Describe the purpose")
def cfSpaceName = ComponentAccessor.customFieldManager.getCustomFieldObjectByName("New Desired Space Name")
def cfSpaceKey  = ComponentAccessor.customFieldManager.getCustomFieldObjectByName("Desired Space Key")

def summary   = (issue.getCustomFieldValue(cfSummary) ?: issue.summary) as String
def purpose   = issue.getCustomFieldValue(cfPurpose) as String ?: ""
def spaceName = issue.getCustomFieldValue(cfSpaceName) as String
def spaceKey  = issue.getCustomFieldValue(cfSpaceKey)  as String

if (!spaceName || !spaceKey) {
    log.error "Confluence-Space: missing name/key; name='${spaceName}', key='${spaceKey}'"
    return false
}

// ── 2. Build your JSON payload ──────────────────────────────────────────
def payload = new JsonBuilder([
    key:   spaceKey,
    name:  spaceName,
    description: [
      plain: [
        value:          "${summary}\n\n${purpose}",
        representation: "plain"
      ]
    ]
]).toString()

// ── 3. Look up your Confluence Application Link ─────────────────────────
def appLinkSvc     = ComponentAccessor.getOSGiComponentInstanceOfType(ApplicationLinkService)
def confluenceLink = appLinkSvc.getPrimaryApplicationLink(Confluence)
if (!confluenceLink) {
    log.error "No Confluence application link found!"
    return false
}
def factory = confluenceLink.createAuthenticatedRequestFactory()

// ── 4. Create & execute your request ──────────────────────────────────
def req = factory
  .createRequest(Request.MethodType.POST, "/rest/api/space")
req
  .setHeader("Content-Type", "application/json")
  .setEntity(payload, ContentTypes.APPLICATION_JSON)

def resp = req.execute()

// ── 5. Handle success, conflicts, and errors ───────────────────────────
def code = resp.statusCode
if (code in 200..299) {
    log.info "Confluence space '${spaceKey}' created (HTTP ${code})."
    // Bonus: add a comment to the Jira issue with the new space URL
    def comment = ComponentAccessor.commentManager
      .create(issue, ComponentAccessor.jiraAuthenticationContext.loggedInUser,
              "✅ New Confluence space created: ${confluenceLink.displayUrl}/display/${spaceKey}",
              true)
    return true
}
else if (code == 409) {
    log.warn "Space key '${spaceKey}' already exists (HTTP 409); skipping creation."
    return true
}
else {
    def err = resp.responseBodyAsString
    log.error "Failed to create Confluence space: HTTP ${code} — ${err}"
    return false
}