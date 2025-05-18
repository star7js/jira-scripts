import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.project.Project
import com.atlassian.jira.security.roles.ProjectRole
import com.atlassian.jira.security.roles.ProjectRoleManager
import com.atlassian.jira.bc.projectroles.ProjectRoleService // Correct service for modifying roles
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.user.util.UserManager
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.util.SimpleErrorCollection
import com.atlassian.jira.security.roles.ProjectRoleActor // For actor types
import com.atlassian.jira.security.groups.GroupManager // For validating/getting groups by name

// For logging
import org.apache.log4j.Logger
import org.apache.log4j.Level

def log = Logger.getLogger("com.example.ProjectRoleAutomation")
log.setLevel(Level.DEBUG) // Set to INFO for production

// --- Configuration ---
// Custom Field Names (ensure these match exactly or use IDs)
final String ADMIN_USERS_FIELD_NAME = "Which Users Should Have Admin Rights?"
final String DEV_GROUPS_FIELD_NAME = "Which User Groups Should Have Developer Rights?" // Change if using ID
final String DEV_GROUPS_FIELD_TYPE = "GROUP_PICKER" // or "TEXT_FIELD"

// Project Role Names
final String ADMIN_ROLE_NAME = "Administrators"
final String DEVELOPER_ROLE_NAME = "Developers"
// --- End Configuration ---

// Get necessary Jira services
def customFieldManager = ComponentAccessor.getCustomFieldManager()
def projectRoleManager = ComponentAccessor.getComponent(ProjectRoleManager)
def projectRoleService = ComponentAccessor.getComponent(ProjectRoleService)
def groupManager = ComponentAccessor.getGroupManager()
def jiraAuthenticationContext = ComponentAccessor.getJiraAuthenticationContext()
def loggedInUser = jiraAuthenticationContext.getLoggedInUser() // User performing the action

// --- Get the issue and project context ---
// 'issue' variable is usually available in post-functions or listeners.
// If in a listener, it might be event.issue
// If testing in Script Console, you might need to fetch an issue:
// def issueManager = ComponentAccessor.getIssueManager()
// Issue issue = issueManager.getIssueObject("YOURPROJECT-123") // Replace with a valid issue key

if (!issue) {
    log.error("Issue object is not available. Script cannot proceed.")
    return // Or throw new Exception("Issue object not found")
}

Project project = issue.getProjectObject()
if (!project) {
    log.error("Project object not found for issue ${issue.key}. Script cannot proceed.")
    return
}
log.debug("Processing role assignments for project: ${project.key} based on issue: ${issue.key}")

// Helper function to add actors to a project role
def addActorsToProjectRole(Project project, String roleName, Collection<String> actorNames, String actorType) {
    if (actorNames.isEmpty()) {
        log.debug("No ${actorType}s to add to role '${roleName}'.")
        return
    }

    ProjectRole projectRole = projectRoleManager.getProjectRole(roleName)
    if (!projectRole) {
        log.error("Project Role '${roleName}' not found. Cannot add actors to project ${project.key}.")
        return
    }

    def errorCollection = new SimpleErrorCollection()

    // The ProjectRoleService.addActorsToProjectRole method requires a Collection<String> of actor names
    // For users, these are user keys. For groups, these are group names.
    projectRoleService.addActorsToProjectRole(
        actorNames,      // Collection<String> actors (user keys or group names)
        projectRole,     // ProjectRole projectRole
        project,         // Project project
        actorType,       // String actorType (ProjectRoleActor.USER_ROLE_ACTOR_TYPE or ProjectRoleActor.GROUP_ROLE_ACTOR_TYPE)
        errorCollection  // ErrorCollection errorCollection
    )

    if (errorCollection.hasAnyErrors()) {
        log.error("Errors adding ${actorType}s '${actorNames.join(', ')}' to role '${roleName}' in project '${project.key}':")
        errorCollection.getErrors().each { key, value -> log.error("- ${key}: ${value}") }
        errorCollection.getErrorMessages().each { msg -> log.error("- ${msg}") }
    } else {
        log.info("Successfully added ${actorType}s '${actorNames.join(', ')}' to role '${roleName}' in project '${project.key}'.")
    }
}

// 1. Add users to "Administrators" role
CustomField adminUsersCf = customFieldManager.getCustomFieldObjects(issue).find { it.name == ADMIN_USERS_FIELD_NAME }
if (!adminUsersCf) {
    log.warn("Custom field '${ADMIN_USERS_FIELD_NAME}' not found on issue ${issue.key}.")
} else {
    // User Picker (Multiple Users) returns a Collection<ApplicationUser>
    Collection<ApplicationUser> usersToAdd = issue.getCustomFieldValue(adminUsersCf) as Collection<ApplicationUser>
    if (usersToAdd) {
        List<String> userKeys = usersToAdd.collect { it.key } // ProjectRoleService needs user keys
        if (userKeys) {
            log.debug("Users to add to '${ADMIN_ROLE_NAME}': ${userKeys.join(', ')}")
            addActorsToProjectRole(project, ADMIN_ROLE_NAME, userKeys, ProjectRoleActor.USER_ROLE_ACTOR_TYPE)
        }
    } else {
        log.debug("No users selected in field '${ADMIN_USERS_FIELD_NAME}'.")
    }
}

// 2. Add groups to "Developers" role
CustomField devGroupsCf = customFieldManager.getCustomFieldObjects(issue).find { it.name == DEV_GROUPS_FIELD_NAME }
if (!devGroupsCf) {
    log.warn("Custom field '${DEV_GROUPS_FIELD_NAME}' not found on issue ${issue.key}.")
} else {
    List<String> groupNamesToAdd = []

    if (DEV_GROUPS_FIELD_TYPE == "GROUP_PICKER") {
        // Group Picker (Multiple Groups) usually returns Collection<com.atlassian.jira.user.Group>
        // or sometimes Collection<String> of group names depending on context/version.
        def groupFieldValue = issue.getCustomFieldValue(devGroupsCf)
        if (groupFieldValue instanceof Collection) {
            Collection groupsSelected = groupFieldValue as Collection
            groupsSelected.each { groupItem ->
                if (groupItem instanceof com.atlassian.jira.user.Group) {
                    groupNamesToAdd.add((groupItem as com.atlassian.jira.user.Group).getName())
                } else if (groupItem instanceof String) { // If it directly returns group names
                    // Validate if the group name is a real group
                    if (groupManager.getGroup(groupItem as String)) {
                        groupNamesToAdd.add(groupItem as String)
                    } else {
                        log.warn("Group name '${groupItem}' from Group Picker field is not a valid group. Skipping.")
                    }
                } else if (groupItem != null) {
                    log.warn("Unexpected item type in group picker field '${DEV_GROUPS_FIELD_NAME}': ${groupItem.getClass().getName()}")
                }
            }
        } else if (groupFieldValue != null) {
             log.warn("Unexpected type for group picker field '${DEV_GROUPS_FIELD_NAME}': ${groupFieldValue.getClass().getName()}")
        }

    } else if (DEV_GROUPS_FIELD_TYPE == "TEXT_FIELD") {
        // Text Field (Single Line or Multi Line) returns a String
        String groupNamesText = issue.getCustomFieldValue(devGroupsCf) as String
        if (groupNamesText) {
            // Assuming comma-separated group names
            List<String> potentialGroupNames = groupNamesText.split(',').collect { it.trim() }.findAll { it } // Split, trim, remove empty
            potentialGroupNames.each { groupName ->
                if (groupManager.getGroup(groupName)) {
                    groupNamesToAdd.add(groupName)
                } else {
                    log.warn("Group '${groupName}' from text field '${DEV_GROUPS_FIELD_NAME}' does not exist. Skipping.")
                }
            }
        }
    } else {
        log.error("Unknown DEV_GROUPS_FIELD_TYPE: ${DEV_GROUPS_FIELD_TYPE}")
    }

    if (groupNamesToAdd) {
        log.debug("Groups to add to '${DEVELOPER_ROLE_NAME}': ${groupNamesToAdd.join(', ')}")
        addActorsToProjectRole(project, DEVELOPER_ROLE_NAME, groupNamesToAdd, ProjectRoleActor.GROUP_ROLE_ACTOR_TYPE)
    } else {
        log.debug("No valid groups found or selected in field '${DEV_GROUPS_FIELD_NAME}'.")
    }
}

log.debug("Finished processing role assignments for project: ${project.key}")
