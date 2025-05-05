import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.permission.ProjectPermission
import com.atlassian.jira.scheme.Scheme
import com.atlassian.jira.scheme.SchemeEntity
import com.atlassian.jira.security.plugin.ProjectPermissionKey
import org.slf4j.Logger
import org.slf4j.LoggerFactory

// Imports for Issue Creation
import com.atlassian.jira.bc.issue.IssueService
import com.atlassian.jira.config.ConstantsManager
import com.atlassian.jira.issue.IssueInputParameters
import com.atlassian.jira.issue.issuetype.IssueType
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.project.Project // Added for Project ID lookup

// Use SLF4J for logging within scheduled jobs/services
def log = LoggerFactory.getLogger("com.permissions.AuditPermissionSchemesAndCreateIssue") // Updated logger name

// --- Configuration (Same as the listener) ---
def restrictedGroupNames = [
    "jira-software-users",
    "jira-servicedesk-users"
    // Add any other group names here
] as Set<String>

// Add the keys of application roles you want to restrict
def restrictedRoleKeys = [
    "jira-software",    // Default key for Jira Software access
    "jira-servicedesk", // Default key for Jira Service Management access
    "jira-core"
    // Add any other role keys here, e.g., a custom role key
] as Set<String>

def restrictLoggedInUsers = true // Set to true to block 'Anyone logged in'
def restrictPublicAccess = true // Set to true to block 'Anyone'/'Public'
// --- End Configuration ---

log.info("Starting Permission Scheme Audit Job with Issue Creation...")

def permissionSchemeManager = ComponentAccessor.getPermissionSchemeManager()
def permissionManager = ComponentAccessor.getPermissionManager()
// Get services needed for issue creation
def issueService = ComponentAccessor.getComponent(IssueService)
def constantsManager = ComponentAccessor.getConstantsManager()
def authenticationContext = ComponentAccessor.getJiraAuthenticationContext()
def projectManager = ComponentAccessor.getProjectManager() // Added for Project ID lookup

// Get the user the script is running as (set in the Scheduled Job config)
def currentUser = authenticationContext.getLoggedInUser()

// Dynamically find the ID for the "Task" issue type
// NOTE: Replace "Task" if you use a different name
def taskIssueType = constantsManager.allIssueTypeObjects.find { it.name == "Task" }
if (!taskIssueType) {
    log.error("Could not find Issue Type named 'Task'. Cannot create violation issues.")
    // Consider throwing an exception or returning early if issue creation is critical
    // return "Error: Issue Type 'Task' not found."
}
def taskTypeId = taskIssueType?.id

// Placeholder for the project key where violation issues will be created
// *** IMPORTANT: Ensure this project exists and the 'Run As User' has permission to create issues in it ***
def violationProjectKey = "AUDIT"
def violationProject = projectManager.getProjectByCurrentKey(violationProjectKey)
if (!violationProject) {
     log.error("Could not find project with key '${violationProjectKey}'. Cannot create violation issues.")
     // return "Error: Project '${violationProjectKey}' not found."
}
def violationProjectId = violationProject?.id

// Check if the user is valid before starting the main loop
if (!currentUser) {
    log.error("Could not determine the current user. Make sure the scheduled job has a valid 'Run As User'. Cannot create issues.")
    // return "Error: Current user not found."
}
if (!taskTypeId) {
     log.error("Cannot proceed without a valid Task Issue Type ID.")
     // return "Error: Task Issue Type ID not found."
}
if (!violationProjectId) {
     log.error("Cannot proceed without a valid Project ID for '${violationProjectKey}'.")
     // return "Error: Violation Project ID not found."
}


// Get all permission schemes
def allSchemes = permissionSchemeManager.getSchemeObjects()

int schemesChecked = 0
int totalViolationsFound = 0

allSchemes.each { Scheme permissionScheme ->
    schemesChecked++
    log.debug("Checking scheme: ${permissionScheme.getName()} (ID: ${permissionScheme.getId()})")

    def currentEntities = permissionScheme.getEntities()

    if (!currentEntities) {
        log.debug("No entities (grants) found for scheme ID: ${permissionScheme.getId()}. Skipping.")
        return // Groovy's 'return' here skips to the next iteration of .each
    }

    boolean schemeViolationFound = false
    def schemeViolations = [] // List to hold violations for *this* scheme

    currentEntities.each { SchemeEntity entity ->
        def holderType = entity.getType()
        def holderParameter = entity.getParameter()
        // getEntityTypeId() returns ProjectPermissionKey for standard permissions
        def permissionIdObject = entity.getEntityTypeId()

        // Initialize permission name
        String permissionName = "Unknown Permission"

        // Check if we received a ProjectPermissionKey as expected
        if (permissionIdObject instanceof ProjectPermissionKey) {
            ProjectPermissionKey permissionKey = permissionIdObject as ProjectPermissionKey
            // Use .permissionKey() to get the string representation
            permissionName = permissionKey.permissionKey() // Default name to the key string

            // Try to get the full ProjectPermission object to get the user-friendly name
            def optPermission = permissionManager.getProjectPermission(permissionKey)
            if (optPermission.isDefined()) {
                // ProjectPermission objects use getKey() for the i18n key
                permissionName = optPermission.get().getKey() // Get the i18n key
            } else {
                log.warn("Scheme '${permissionScheme.getName()}' (ID: ${permissionScheme.getId()}): Could not find ProjectPermission details for key: ${permissionKey.permissionKey()}")
            }
        } else {
            // Log if we get something unexpected (e.g., null or a different type)
            permissionName = "Unknown Permission (Type: ${permissionIdObject?.getClass()?.getName() ?: 'null' })"
            log.warn("Scheme '${permissionScheme.getName()}' (ID: ${permissionScheme.getId()}): Unexpected permission identifier type: ${permissionIdObject?.getClass()?.getName()} for entity type '${holderType}', parameter '${holderParameter}'. Value: ${permissionIdObject}")
        }

        log.warn("Checking entity in scheme '${permissionScheme.getName()}': Type='${holderType}', Parameter='${holderParameter}', Permission='${permissionName}'")

        // Check for restricted groups (ensure parameter is not null first)
        if (holderType == "group" && holderParameter != null && holderParameter in restrictedGroupNames) {
            schemeViolationFound = true
            schemeViolations << "Restricted Group '${holderParameter}' for permission '${permissionName}'"
        }

        // Check for 'Anyone logged in' (uses applicationRole with null param in this env)
        if (restrictLoggedInUsers && holderType == "applicationRole" && holderParameter == null) {
            schemeViolationFound = true
            schemeViolations << "'Anyone logged in' for permission '${permissionName}'"
        }

        // Check for Public/Anyone (uses group with null param in this env)
        if (restrictPublicAccess && holderType == "group" && holderParameter == null) {
            schemeViolationFound = true
            schemeViolations << "'Public / Anyone on the web' for permission '${permissionName}'"
        }

        // Check for restricted Application Roles (ensure parameter is not null first)
        if (holderType == "applicationRole" && holderParameter != null && holderParameter in restrictedRoleKeys) {
            schemeViolationFound = true
            // holderParameter is the role key (e.g., "jira-software")
            schemeViolations << "Restricted Application Role '${holderParameter}' for permission '${permissionName}'"
        }
    }

    // Log violations found for this specific scheme and create Jira Issue
    if (schemeViolationFound) {
        totalViolationsFound++
        def violationSummary = schemeViolations.join('; ')
        // Log as WARNING or ERROR depending on severity preference
        log.warn("VIOLATION DETECTED in Permission Scheme '${permissionScheme.getName()}' (ID: ${permissionScheme.getId()}). Restricted grants found: ${violationSummary}")

        // --- Create Jira Issue ---
        // Only proceed if we have a user, issue type ID, and project ID
        if (currentUser && taskTypeId && violationProjectId) {
            IssueInputParameters issueInputParameters = issueService.newIssueInputParameters()
            issueInputParameters.setProjectId(violationProjectId)
            issueInputParameters.setIssueTypeId(taskTypeId)
            issueInputParameters.setReporterId(currentUser.key)
            issueInputParameters.setSummary("Permission Scheme Violation: ${permissionScheme.getName()}")
            // Construct description with details
            def description = """Violation detected in Permission Scheme: *${permissionScheme.getName()}* (ID: ${permissionScheme.getId()})

The following restricted grants were found:
${schemeViolations.collect { "* ${it}" }.join("\n")}

Please investigate and remove the incorrect grants."""
            issueInputParameters.setDescription(description)
            // Optional: Set Assignee, Priority, etc.
            // issueInputParameters.setAssigneeId("some_user_key")
            // issueInputParameters.setPriorityId(constantsManager.getPriorityObjects().find {it.name == 'High'}?.id)

            log.debug("Attempting to create violation issue in project ${violationProjectKey} for scheme ${permissionScheme.getName()}")
            IssueService.CreateValidationResult validationResult = issueService.validateCreate(currentUser, issueInputParameters)

            if (validationResult.isValid()) {
                IssueService.IssueResult createResult = issueService.create(currentUser, validationResult)
                if (createResult.isValid()) {
                    log.info("Successfully created violation issue ${createResult.getIssue().getKey()} for scheme '${permissionScheme.getName()}'")
                } else {
                    log.error("Failed to create violation issue for scheme '${permissionScheme.getName()}'. Errors: ${createResult.getErrorCollection()}")
                }
            } else {
                log.error("Validation failed for creating violation issue for scheme '${permissionScheme.getName()}'. Errors: ${validationResult.getErrorCollection()}")
            }
        } else {
             log.warn("Skipping issue creation for scheme '${permissionScheme.getName()}' due to missing current user, Task issue type ID, or Project ID.")
        }
        // --- End Create Jira Issue ---
    }
}

log.info("Permission Scheme Audit Job finished. Checked ${schemesChecked} schemes. Found violations in ${totalViolationsFound} schemes.")

// Optional: Return a summary string which might be logged by the scheduler
return "Audit Complete. Checked: ${schemesChecked}, Violations Found: ${totalViolationsFound}" 
