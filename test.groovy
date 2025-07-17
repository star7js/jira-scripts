import com.atlassian.jira.bc.project.component.ProjectComponentManager
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.entity.property.EntityPropertyService
import com.atlassian.jira.event.project.ProjectCreatedEvent
import com.atlassian.jira.issue.fields.config.FieldConfig
import com.atlassian.jira.issue.fields.config.FieldConfigScheme
import com.atlassian.jira.issue.fields.config.manager.PrioritySchemeManager
import com.atlassian.jira.issue.fields.layout.field.EditableFieldLayout
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutManager
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutScheme
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutSchemeEntity
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutSchemeImpl
import com.atlassian.jira.notification.NotificationScheme
import com.atlassian.jira.notification.NotificationSchemeManager
import com.atlassian.jira.permission.PermissionSchemeManager
import com.atlassian.jira.project.Project
import com.atlassian.jira.scheme.SchemeManager
import com.google.common.collect.ImmutableList
import org.apache.log4j.Logger

// Reference IDs for template schemes
def permissionRefSchemeId = 16287L // Use Long for IDs
def notificationRefSchemeId = 12380L

// Get components and event data
def log = Logger.getLogger("com.onresolve.scriptrunner.runner.ScriptRunnerImpl")
def projectManager = ComponentAccessor.getProjectManager()
def permissionSchemeManager = ComponentAccessor.getPermissionSchemeManager()
def notificationSchemeManager = ComponentAccessor.getNotificationSchemeManager()
def prioritySchemeManager = ComponentAccessor.getComponent(PrioritySchemeManager.class)
def fieldLayoutManager = ComponentAccessor.getFieldLayoutManager()
def entityPropertyService = ComponentAccessor.getComponent(EntityPropertyService.class)
def event = event as ProjectCreatedEvent
def project = event.project
def noSpaceProjectName = project.name.toLowerCase().replaceAll("\\s+", "")

// Skip if this is a script-managed project
def currentUser = event.user ?: ComponentAccessor.jiraAuthenticationContext.loggedInUser // Fallback to authenticated user
def entityIdentifier = new EntityPropertyService.EntityIdentifier("Project", project.id)
def propertyResult = entityPropertyService.getEntityProperty(currentUser, entityIdentifier, "managed_by_script")
if (propertyResult.valid && propertyResult.entityProperty.value == "true") {
    log.info("Skipping listener for managed project ${project.key}")
    return
}

// Proceed with original logic
// Helper to generate scheme name
def getSchemeName(String prefix, String suffix = "") {
    return prefix + "-jira-" + noSpaceProjectName + "-" + suffix
}

// Helper to get scheme by name (generic for different managers)
def getSchemeByName(manager, String schemeName) {
    if (manager instanceof PermissionSchemeManager) {
        return manager.getSchemeObjects().find { it.name == schemeName }
    } else if (manager instanceof NotificationSchemeManager) {
        return manager.getSchemeObjects().find { it.name == schemeName }
    } else if (manager instanceof PrioritySchemeManager) {
        return manager.getAllSchemes().find { it.name == schemeName }
    } else if (manager instanceof FieldLayoutManager) {
        return manager.getFieldLayoutSchemes().find { it.name == schemeName }
    }
    return null
}

// Handle permission scheme
def handlePermissionScheme() {
    def schemeName = getSchemeName("app", "permissions-scheme")
    try {
        def targetScheme = getSchemeByName(permissionSchemeManager, schemeName)
        if (!targetScheme) {
            def refScheme = permissionSchemeManager.getSchemeObject(permissionRefSchemeId)
            if (!refScheme) {
                log.error("Reference permission scheme ID ${permissionRefSchemeId} not found")
                return
            }
            targetScheme = permissionSchemeManager.copyScheme(refScheme)
            targetScheme.name = schemeName
            permissionSchemeManager.updatePermissionScheme(targetScheme)
            log.info("Created new permission scheme: ${schemeName}")
        } else {
            log.info("Using existing permission scheme: ${schemeName}")
        }
        if (permissionSchemeManager.getSchemeFor(project)?.id != targetScheme.id) {
            permissionSchemeManager.removeSchemesFromProject(project)
            permissionSchemeManager.addSchemeToProject(project, targetScheme)
            log.info("Assigned permission scheme to project ${project.key}")
        }
    } catch (Exception e) {
        log.error("Error handling permission scheme: ${e.message}", e)
    }
}

// Handle notification scheme
def handleNotificationScheme() {
    def schemeName = getSchemeName("app", "notification-scheme")
    try {
        def targetScheme = getSchemeByName(notificationSchemeManager, schemeName) as NotificationScheme
        if (!targetScheme) {
            def refScheme = notificationSchemeManager.getSchemeObject(notificationRefSchemeId)
            if (!refScheme) {
                log.error("Reference notification scheme ID ${notificationRefSchemeId} not found")
                return
            }
            targetScheme = notificationSchemeManager.copyScheme(refScheme)
            targetScheme.name = schemeName
            notificationSchemeManager.updateScheme(targetScheme)
            log.info("Created new notification scheme: ${schemeName}")
        } else {
            log.info("Using existing notification scheme: ${schemeName}")
        }
        if (notificationSchemeManager.getNotificationSchemeForProject(project)?.id != targetScheme.id) {
            notificationSchemeManager.createSchemeAssociation(project, targetScheme)
            log.info("Assigned notification scheme to project ${project.key}")
        }
    } catch (Exception e) {
        log.error("Error handling notification scheme: ${e.message}", e)
    }
}

// Handle priority scheme
def handlePriorityScheme() {
    def schemeName = getSchemeName("app", "priority-scheme")
    try {
        def targetScheme = getSchemeByName(prioritySchemeManager, schemeName) as FieldConfigScheme
        if (!targetScheme) {
            // Create with priorities "2", "3", "4" (adjust IDs as needed for your instance)
            targetScheme = prioritySchemeManager.createScheme(schemeName, "Custom priorities for ${project.name}", ImmutableList.of("2", "3", "4"))
            def fieldConfig = targetScheme.getOneAndOnlyConfig()
            if (fieldConfig) {
                prioritySchemeManager.setDefaultOption(fieldConfig, "3") // Set default to "3"
            }
            log.info("Created new priority scheme: ${schemeName}")
        } else {
            log.info("Using existing priority scheme: ${schemeName}")
        }
        if (prioritySchemeManager.getSchemeFor(project)?.name != schemeName) {
            prioritySchemeManager.assignProject(targetScheme, project)
            log.info("Assigned priority scheme to project ${project.key}")
        }
    } catch (Exception e) {
        log.error("Error handling priority scheme: ${e.message}", e)
    }
}

// Handle field layout scheme
def handleFieldLayoutScheme() {
    def fieldConfigName = getSchemeName("app", "field-config")
    def layoutSchemeName = getSchemeName("app", "field-config-scheme")
    try {
        // Get or create editable field layout from default
        def defaultLayout = fieldLayoutManager.getDefaultFieldLayout()
        def editableLayout = fieldLayoutManager.getEditableFieldLayout(defaultLayout.id) ?: fieldLayoutManager.createEditableFieldLayout()
        editableLayout.name = fieldConfigName
        fieldLayoutManager.storeEditableFieldLayout(editableLayout)
        def newFieldLayout = fieldLayoutManager.getFieldLayout(editableLayout.id)

        // Get or create field layout scheme
        def targetLayoutScheme = getSchemeByName(fieldLayoutManager, layoutSchemeName) as FieldLayoutScheme
        if (!targetLayoutScheme) {
            targetLayoutScheme = new FieldLayoutSchemeImpl(fieldLayoutManager, null)
            targetLayoutScheme.name = layoutSchemeName
            targetLayoutScheme.description = "Custom field layout for ${project.name}"
            targetLayoutScheme = fieldLayoutManager.createFieldLayoutScheme(targetLayoutScheme)
            def entity = new FieldLayoutSchemeEntity(fieldLayoutManager, null, null, newFieldLayout.id)
            fieldLayoutManager.createFieldLayoutSchemeEntity(targetLayoutScheme, entity)
            log.info("Created new field layout scheme: ${layoutSchemeName}")
        } else {
            log.info("Using existing field layout scheme: ${layoutSchemeName}")
        }
        // Assign to project (remove old if needed)
        fieldLayoutManager.removeSchemeAssociation(project, targetLayoutScheme)
        fieldLayoutManager.addSchemeAssociation(project, targetLayoutScheme.id)
        log.info("Assigned field layout scheme to project ${project.key}")
    } catch (Exception e) {
        log.error("Error handling field layout scheme: ${e.message}", e)
    }
}

// Execute handlers
log.info("Project created: ${project.name} (Key: ${project.key}) by user: ${event.user?.displayName ?: 'Unknown'}")
handlePermissionScheme()
handleNotificationScheme()
handlePriorityScheme()
handleFieldLayoutScheme()