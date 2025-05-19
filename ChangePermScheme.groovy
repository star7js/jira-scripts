import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.project.Project
import com.atlassian.jira.scheme.Scheme
import com.atlassian.jira.permission.PermissionSchemeManager
import com.atlassian.jira.scheme.SchemePermission
import org.apache.log4j.Logger
import org.apache.log4j.Level

// --- Configuration ---
// SET THESE VALUES
final String TARGET_PROJECT_KEY = "NEWPROJ" // Key of the project to apply the new scheme to
final String SOURCE_SCHEME_NAME = "Special Global Permissions" // Name of the scheme to copy FROM
final String NEW_SCHEME_NAME_SUFFIX = "Special Permissions" // Suffix for the new scheme name
// --- End Configuration ---

Logger log = Logger.getLogger("com.example.CopyAndApplyPermissionScheme")
log.setLevel(Level.DEBUG) // Set to INFO for less verbosity in production

def projectManager = ComponentAccessor.getProjectManager()
def permissionSchemeManager = ComponentAccessor.getPermissionSchemeManager()

try {
    log.info("Starting permission scheme copy and apply process...")

    // 1. Get the target project
    Project targetProject = projectManager.getProjectObjByKey(TARGET_PROJECT_KEY)
    if (!targetProject) {
        log.error("Target project with key '${TARGET_PROJECT_KEY}' not found.")
        return "ERROR: Target project '${TARGET_PROJECT_KEY}' not found."
    }
    log.info("Target project: ${targetProject.getName()} (${targetProject.getKey()})")

    // 2. Get the source permission scheme
    Scheme sourceScheme = permissionSchemeManager.getSchemeObject(SOURCE_SCHEME_NAME)
    if (!sourceScheme) {
        log.error("Source permission scheme with name '${SOURCE_SCHEME_NAME}' not found.")
        return "ERROR: Source scheme '${SOURCE_SCHEME_NAME}' not found."
    }
    log.info("Source scheme: ${sourceScheme.getName()} (ID: ${sourceScheme.getId()})")

    // 3. Define the new unique scheme name and description
    String newSchemeName = "${targetProject.getKey()} - ${NEW_SCHEME_NAME_SUFFIX}"
    String newSchemeDescription = "Custom permission scheme for project ${targetProject.getName()} (${targetProject.getKey()}), copied from ${sourceScheme.getName()}."

    Scheme newScheme = permissionSchemeManager.getSchemeObject(newSchemeName)

    if (newScheme != null) {
        log.warn("A scheme named '${newSchemeName}' (ID: ${newScheme.getId()}) already exists.")
        // Check if it's associated with other projects
        def projectsUsingThisScheme = permissionSchemeManager.getProjects(newScheme)
        def otherProjects = projectsUsingThisScheme.findAll { it.id != targetProject.id }

        if (!otherProjects.isEmpty()) {
            log.error("Scheme '${newSchemeName}' already exists and is used by other projects: ${otherProjects*.key}. " +
                      "Cannot safely reuse. Please choose a different NEW_SCHEME_NAME_SUFFIX or resolve manually.")
            return "ERROR: Scheme '${newSchemeName}' exists and is shared. Aborting."
        } else {
            log.info("Scheme '${newSchemeName}' exists but is either unused or only associated with the target project. Reusing it.")
            // Clear existing permissions from this scheme before re-populating
            Collection<SchemePermission> existingPermissions = permissionSchemeManager.getPermissions(newScheme.getId())
            log.info("Clearing ${existingPermissions.size()} existing permissions from scheme '${newSchemeName}'...")
            existingPermissions.each { sp ->
                permissionSchemeManager.deleteSchemePermission(sp)
            }
            log.info("Finished clearing permissions from '${newSchemeName}'.")
        }
    } else {
        log.info("Creating new permission scheme: '${newSchemeName}'")
        newScheme = permissionSchemeManager.createScheme(newSchemeName, newSchemeDescription)
        if (newScheme == null) {
            log.error("Failed to create new permission scheme '${newSchemeName}'.")
            return "ERROR: Failed to create permission scheme '${newSchemeName}'."
        }
        log.info("Successfully created new scheme: ${newScheme.getName()} (ID: ${newScheme.getId()})")
    }

    // 4. Copy permissions from source scheme to new scheme
    log.info("Copying permissions from '${sourceScheme.getName()}' to '${newScheme.getName()}'...")
    Collection<SchemePermission> permissionsToCopy = permissionSchemeManager.getPermissions(sourceScheme.getId())
    int copiedCount = 0
    permissionsToCopy.each { SchemePermission sp ->
        // SchemePermission objects contain:
        // sp.getPermission() -> The numeric ID of the permission (e.g., 10 for BROWSE_PROJECTS)
        // sp.getType() -> The type of grantee (e.g., "user", "group", "projectrole", "applicationrole")
        // sp.getParameter() -> The specific grantee (e.g., username, group name, project role ID, application role key)
        try {
            permissionSchemeManager.addPermissionToScheme(newScheme, sp.getPermission(), sp.getParameter(), sp.getType())
            log.debug("Copied permission: ID=${sp.getPermission()}, Type=${sp.getType()}, Param='${sp.getParameter()}'")
            copiedCount++
        } catch (Exception e) {
            log.error("Failed to copy permission: ID=${sp.getPermission()}, Type=${sp.getType()}, Param='${sp.getParameter()}'. Error: ${e.message}", e)
        }
    }
    log.info("Copied ${copiedCount} permission grants from '${sourceScheme.getName()}' to '${newScheme.getName()}'.")

    // 5. Remove existing scheme associations from the project
    log.info("Removing existing permission scheme associations from project '${targetProject.getKey()}'...")
    permissionSchemeManager.removeSchemesFromProject(targetProject)
    log.info("Existing scheme associations removed.")

    // 6. Apply the new scheme to the project
    log.info("Applying new scheme '${newScheme.getName()}' to project '${targetProject.getKey()}'...")
    permissionSchemeManager.addSchemeToProject(targetProject, newScheme)
    log.info("Successfully applied scheme '${newScheme.getName()}' to project '${targetProject.getKey()}'.")

    return "SUCCESS: Permission scheme '${newScheme.getName()}' (ID: ${newScheme.getId()}) copied from '${sourceScheme.getName()}' and applied to project '${targetProject.getKey()}'."

} catch (Exception e) {
    log.error("An unexpected error occurred: ${e.message}", e)
    StringWriter sw = new StringWriter()
    e.printStackTrace(new PrintWriter(sw))
    return "ERROR: An unexpected error occurred. Check logs. Details: ${e.getMessage()}\n${sw.toString()}"
}
