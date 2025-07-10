// Updated project_event_listener.groovy (with more extensive changes for robustness, including caching, better validation, and consistency with the creation script)

import com.google.common.collect.ImmutableList
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.project.Project
import com.atlassian.jira.project.ProjectManager
import com.atlassian.jira.issue.fields.config.FieldConfig
import com.atlassian.jira.issue.fields.config.FieldConfigScheme
import com.atlassian.jira.issue.fields.config.manager.PrioritySchemeManager
import com.atlassian.jira.config.ConstantManager
import com.atlassian.jira.issue.fields.layout.field.*
import com.atlassian.beehive.ClusterLockService
import com.atlassian.cache.CacheManager
import com.atlassian.cache.Cache
import com.atlassian.cache.CacheSettingsBuilder
import com.atlassian.jira.util.SimpleErrorCollection
import org.apache.log4j.Logger
import java.util.concurrent.TimeUnit

//update that these for production
def permissionRefSchemeID = 10207;
def notificationRefSchemeID = 12300;

private static final Logger log = Logger.getLogger("ProjectEventListener")
private static final String LOCK_PREFIX = "project-creation-lock-"
private static final int LOCK_TIMEOUT_SECONDS = 30
private static final String CACHE_NAME = "project-setup-cache"

ProjectManager projectManager = ComponentAccessor.getProjectManager()
def permissionSchemeManager = ComponentAccessor.permissionSchemeManager

log.warn(event.user.displayName + " created project")

def project = projectManager.getProjectObjByKey(event.getProject().getKey())

if (!project) {
    log.error("Project not found after creation event")
    return
}

log.warn("project found " + project.getName());

def noSpaceProjectName = project.getName().replaceAll(" ", "").toLowerCase()

def clusterLockService = ComponentAccessor.getComponent(ClusterLockService.class)
def lockKey = LOCK_PREFIX + project.key
def lock = clusterLockService.getLockForName(lockKey)

if (!lock.tryLock(LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
    log.warn("Skipped setup for project ${project.key} - lock held by another process (likely scripted creation)")
    return
}

try {
    def cacheManager = ComponentAccessor.getComponent(CacheManager.class)
    def cache = cacheManager.getCache(CACHE_NAME, null, 
        new CacheSettingsBuilder()
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .maxEntries(1000)
            .build())
    
    if (cache.get(project.key)) {
        log.warn("Setup already completed for project ${project.key} via cache")
        return
    }
    
    // Permission scheme
    def schemeName = "app-jira-"+noSpaceProjectName+"-permissions-scheme"
    log.warn("permission scheme ready " + schemeName);
    
    def refScheme = permissionSchemeManager.getSchemeObject(permissionRefSchemeID)
    if (!refScheme) {
        log.warn("reference not found")
        return "permission reference not found"
    }
    
    def newPermissionScheme = permissionSchemeManager.getSchemeObject(schemeName)
    if (!newPermissionScheme) {
        log.warn("Copying permission scheme")
        newPermissionScheme = permissionSchemeManager.copyScheme(refScheme)
        newPermissionScheme.setName(schemeName)
        permissionSchemeManager.updateScheme(newPermissionScheme)
    } else {
        log.warn("Permission scheme exists, fetching")
    }
    
    if (permissionSchemeManager.getSchemeFor(project)?.name != schemeName) {
        log.warn("Assigning permission scheme")
        permissionSchemeManager.removeSchemesFromProject(project)
        permissionSchemeManager.addSchemeToProject(project, newPermissionScheme)
    } else {
        log.warn("permission scheme already connected")
    }
    
    // Notification scheme
    def notificationSchemeManager = ComponentAccessor.notificationSchemeManager
    
    def notificationReferenceScheme = notificationSchemeManager.getSchemeObject(notificationRefSchemeID)
    if (!notificationReferenceScheme) {
        log.warn("notification reference not found")
        return "notification reference not found"
    }
    
    def notificationSchemeName = "app-jira-"+noSpaceProjectName+"-notification-scheme"
    def newNotificationScheme = notificationSchemeManager.getSchemeObject(notificationSchemeName)
    if (!newNotificationScheme) {
        log.warn("copying notification scheme")
        newNotificationScheme = notificationSchemeManager.copyScheme(notificationReferenceScheme)
        newNotificationScheme.setName(notificationSchemeName)
        notificationSchemeManager.updateScheme(newNotificationScheme)
    } else {
        log.warn("notification scheme exists fetching existing scheme")
    }
    
    if (notificationSchemeManager.getSchemeFor(project)?.name != notificationSchemeName) {
        log.warn("Connecting notification scheme")
        notificationSchemeManager.removeSchemesFromProject(project)
        notificationSchemeManager.addSchemeToProject(project, newNotificationScheme)
    } else {
        log.warn("scheme already connected")
    }
    
    // Priority scheme
    def prioritySchemeName = "app-jira-"+noSpaceProjectName+"-priority-scheme"
    def prioritySchemeManager = ComponentAccessor.getComponent(PrioritySchemeManager)
    
    def fieldConfigScheme = prioritySchemeManager.getAllSchemes().find { it.name.equalsIgnoreCase(prioritySchemeName) }
    
    if (!fieldConfigScheme) {
        //Creates a new FieldConfigScheme with 3 priorities assigned
        fieldConfigScheme = prioritySchemeManager.createWithDefaultMapping(prioritySchemeName, "Some description", ImmutableList.of("2", "3", "4"));
        def fieldConfig = prioritySchemeManager.getFieldConfigForDefaultMapping(fieldConfigScheme);
        prioritySchemeManager.setDefaultOption(fieldConfig, "3");
        log.warn("created priority scheme")
    } else {
        log.warn("priority scheme exists")
    }
    
    if (prioritySchemeManager.getSchemeFor(project)?.name != prioritySchemeName) {
        log.warn("Assigning priority scheme")
        prioritySchemeManager.removeSchemesFromProject(project)
        prioritySchemeManager.assignToProject(fieldConfigScheme, project);
    } else {
        log.warn("priority scheme already assigned")
    }
    
    // Field configuration
    def fieldConfigSchemeName = "app-jira-" + noSpaceProjectName + "-field-config-scheme"
    
    log.warn("starting field configuration")
    def fieldLayoutManager = ComponentAccessor.getFieldLayoutManager();
    def editableFieldLayout = fieldLayoutManager.getEditableFieldLayouts().find { it.name == fieldConfigSchemeName }
    if (!editableFieldLayout) {
        def defaultFieldLayout = fieldLayoutManager.getEditableDefaultFieldLayout();
        def newEditable = new EditableFieldLayoutImpl(null, defaultFieldLayout.getFieldLayoutItems());
        newEditable.setName(fieldConfigSchemeName);
        newEditable.setDescription(fieldConfigSchemeName);
        editableFieldLayout = fieldLayoutManager.storeAndReturnEditableFieldLayout(newEditable);
    }
    
    def fieldLayoutScheme = fieldLayoutManager.getFieldLayoutSchemes().find { it.name == fieldConfigSchemeName }
    if (!fieldLayoutScheme) {
        fieldLayoutScheme = new FieldLayoutSchemeImpl(fieldLayoutManager, null);
        fieldLayoutScheme.setName(fieldConfigSchemeName);
        fieldLayoutScheme.setDescription(fieldConfigSchemeName);
        fieldLayoutScheme.store();
        
        if (fieldLayoutScheme.getEntities()) {
            fieldLayoutManager.removeFieldLayoutSchemeEntity(fieldLayoutScheme.getEntities().get(0))
        }
        def fieldE = fieldLayoutManager.createFieldLayoutSchemeEntity(fieldLayoutScheme, null, editableFieldLayout.getId())
        fieldLayoutManager.updateFieldLayoutSchemeEntity(fieldE)
        fieldLayoutManager.updateFieldLayoutScheme(fieldLayoutScheme)
    }
    
    if (fieldLayoutManager.getFieldLayoutSchemeForProject(project)?.name != fieldConfigSchemeName) {
        fieldLayoutManager.addSchemeToProject(project, fieldLayoutScheme.getId())
        log.warn("created and tied to project")
    } else {
        log.warn("field layout scheme already assigned")
    }
    
    // Mark as done in cache
    cache.put(project.key, true)
    
} finally {
    lock.unlock()
}