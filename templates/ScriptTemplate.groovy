// =============================================================================
// Script Name: ScriptTemplate.groovy
// Description: Template script for creating new Jira administration scripts
// Author: [Your Name]
// Date: [YYYY-MM-DD]
// Version: 1.0.0
// =============================================================================

// =============================================================================
// IMPORTS
// =============================================================================
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.user.ApplicationUser
import org.slf4j.Logger
import org.slf4j.LoggerFactory

// Add other imports as needed for your specific script
// import com.atlassian.jira.project.Project
// import com.atlassian.jira.issue.Issue
// import groovy.json.JsonSlurper
// import groovy.json.JsonOutput

// =============================================================================
// CONFIGURATION SECTION
// =============================================================================
// ⚠️  IMPORTANT: Update these values before running the script

// Basic Configuration
def scriptEnabled = true                     // ← Set to false to disable script execution
def dryRun = false                           // ← Set to true for testing without making changes

// User Configuration
def targetUsername = 'username'              // ← Change this
def targetGroupName = 'group-name'           // ← Change this

// Project Configuration
def projectKey = 'PROJECT'                   // ← Change this
def issueTypeName = 'Task'                   // ← Change this

// Advanced Configuration
def maxRetries = 3                           // ← Maximum retry attempts
def timeoutMs = 30000                        // ← Request timeout in milliseconds
def batchSize = 100                          // ← Batch size for bulk operations

// =============================================================================
// LOGGING SETUP
// =============================================================================
def log = LoggerFactory.getLogger('com.example.ScriptTemplate')

// =============================================================================
// VALIDATION FUNCTIONS
// =============================================================================
def validateConfiguration() {
    def errors = []
    
    if (!scriptEnabled) {
        log.info("Script is disabled. Exiting.")
        return false
    }
    
    if (!targetUsername?.trim()) {
        errors << "Target username is required"
    }
    
    if (!targetGroupName?.trim()) {
        errors << "Target group name is required"
    }
    
    if (!projectKey?.trim()) {
        errors << "Project key is required"
    }
    
    if (errors) {
        def errorMessage = "Configuration validation failed: ${errors.join(', ')}"
        log.error(errorMessage)
        throw new IllegalArgumentException(errorMessage)
    }
    
    log.info("Configuration validation passed")
    return true
}

def validateInputs(def... inputs) {
    def errors = []
    
    inputs.eachWithIndex { input, index ->
        if (!input) {
            errors << "Input parameter ${index + 1} is required"
        }
    }
    
    if (errors) {
        def errorMessage = "Input validation failed: ${errors.join(', ')}"
        log.error(errorMessage)
        throw new IllegalArgumentException(errorMessage)
    }
}

// =============================================================================
// UTILITY FUNCTIONS
// =============================================================================
def executeWithRetry(Closure operation, int maxRetries = 3) {
    int attempts = 0
    while (attempts < maxRetries) {
        try {
            return operation.call()
        } catch (Exception e) {
            attempts++
            log.warn("Attempt ${attempts} failed: ${e.message}")
            
            if (attempts >= maxRetries) {
                log.error("All ${maxRetries} attempts failed", e)
                throw e
            }
            
            // Exponential backoff
            Thread.sleep(1000 * attempts)
        }
    }
}

def formatDuration(long startTime) {
    def duration = System.currentTimeMillis() - startTime
    def seconds = duration / 1000
    def minutes = seconds / 60
    def hours = minutes / 60
    
    if (hours > 0) {
        return "${hours}h ${minutes % 60}m ${seconds % 60}s"
    } else if (minutes > 0) {
        return "${minutes}m ${seconds % 60}s"
    } else {
        return "${seconds}s"
    }
}

def generateReport(String title, List data, List columns) {
    StringBuilder html = new StringBuilder()
    
    html.append("""
        <html>
        <head>
            <title>${title}</title>
            <style>
                body { font-family: Arial, sans-serif; margin: 20px; }
                table { border-collapse: collapse; width: 100%; margin-top: 20px; }
                th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }
                th { background-color: #f2f2f2; font-weight: bold; }
                tr:nth-child(even) { background-color: #f9f9f9; }
                .error { color: red; }
                .success { color: green; }
                .warning { color: orange; }
                .header { background-color: #4CAF50; color: white; padding: 15px; }
            </style>
        </head>
        <body>
            <div class="header">
                <h1>${title}</h1>
                <p>Generated on: ${new Date()}</p>
            </div>
            <table>
                <thead>
                    <tr>
    """)
    
    columns.each { column ->
        html.append("<th>${column}</th>")
    }
    
    html.append("</tr></thead><tbody>")
    
    data.each { row ->
        html.append("<tr>")
        row.each { cell ->
            html.append("<td>${cell}</td>")
        }
        html.append("</tr>")
    }
    
    html.append("</tbody></table></body></html>")
    
    return html.toString()
}

// =============================================================================
// MAIN SCRIPT LOGIC
// =============================================================================
def main() {
    def startTime = System.currentTimeMillis()
    def results = [:]
    
    try {
        log.info("Starting ScriptTemplate execution...")
        
        // Validate configuration
        if (!validateConfiguration()) {
            return "ERROR: Configuration validation failed"
        }
        
        // Get Jira components
        def userManager = ComponentAccessor.userManager
        def groupManager = ComponentAccessor.groupManager
        def projectManager = ComponentAccessor.projectManager
        
        log.info("Retrieved Jira components successfully")
        
        // Example: Get user
        def user = userManager.getUserByName(targetUsername)
        if (!user) {
            def errorMessage = "User '${targetUsername}' not found"
            log.error(errorMessage)
            return "ERROR: ${errorMessage}"
        }
        
        log.info("Found user: ${user.displayName} (${user.username})")
        
        // Example: Get group
        def group = groupManager.getGroup(targetGroupName)
        if (!group) {
            def errorMessage = "Group '${targetGroupName}' not found"
            log.error(errorMessage)
            return "ERROR: ${errorMessage}"
        }
        
        log.info("Found group: ${group.name}")
        
        // Example: Get project
        def project = projectManager.getProjectByKey(projectKey)
        if (!project) {
            def errorMessage = "Project '${projectKey}' not found"
            log.error(errorMessage)
            return "ERROR: ${errorMessage}"
        }
        
        log.info("Found project: ${project.name} (${project.key})")
        
        // Example: Check if user is in group
        def isUserInGroup = groupManager.isUserInGroup(user, group)
        log.info("User ${user.username} in group ${group.name}: ${isUserInGroup}")
        
        // Example: Get project statistics
        def projectStats = [
            projectName: project.name,
            projectKey: project.key,
            projectLead: project.projectLead?.displayName ?: 'No lead',
            userInGroup: isUserInGroup,
            totalUsers: userManager.totalUserCount,
            totalGroups: groupManager.groupNames.size()
        ]
        
        results.putAll(projectStats)
        
        // Example: Generate report
        if (!dryRun) {
            def reportData = [
                [project.name, project.key, project.projectLead?.displayName ?: 'No lead', isUserInGroup ? 'Yes' : 'No']
            ]
            def reportColumns = ['Project Name', 'Project Key', 'Project Lead', 'User in Group']
            
            def report = generateReport("ScriptTemplate Report", reportData, reportColumns)
            log.info("Generated report successfully")
            
            results.report = report
        } else {
            log.info("DRY RUN: Would generate report here")
        }
        
        def duration = formatDuration(startTime)
        log.info("ScriptTemplate completed successfully in ${duration}")
        
        return "SUCCESS: Script completed in ${duration}. Results: ${results}"
        
    } catch (Exception e) {
        def duration = formatDuration(startTime)
        def errorMessage = "ScriptTemplate failed after ${duration}: ${e.message}"
        log.error(errorMessage, e)
        return "ERROR: ${errorMessage}"
    }
}

// =============================================================================
// SCRIPT EXECUTION
// =============================================================================
// This is the entry point when the script is executed
// The return value will be displayed in the ScriptRunner console

try {
    return main()
} catch (Exception e) {
    log.error("Unexpected error in script execution: ${e.message}", e)
    return "ERROR: Unexpected error: ${e.message}"
}

// =============================================================================
// DOCUMENTATION
// =============================================================================
/*
 * SCRIPT PURPOSE:
 * This template provides a foundation for creating new Jira administration scripts.
 * 
 * PREREQUISITES:
 * - ScriptRunner for Jira app installed
 * - Admin-level permissions
 * - Valid Jira instance (Cloud or Data Center)
 * 
 * CONFIGURATION:
 * - Update the configuration section at the top
 * - Set scriptEnabled to true to run the script
 * - Set dryRun to true for testing without making changes
 * 
 * USAGE:
 * 1. Copy this template to a new file
 * 2. Update the script name, description, and author
 * 3. Modify the configuration section
 * 4. Implement your specific logic in the main() function
 * 5. Test in a development environment first
 * 6. Run in ScriptRunner console or as a scheduled job
 * 
 * OUTPUT:
 * - Success: Returns success message with results
 * - Error: Returns error message with details
 * - Logs: Comprehensive logging for debugging
 * 
 * CUSTOMIZATION:
 * - Add your specific imports at the top
 * - Modify the configuration section for your needs
 * - Implement your business logic in the main() function
 * - Add additional utility functions as needed
 * - Update the documentation section
 * 
 * BEST PRACTICES:
 * - Always validate inputs and configuration
 * - Use comprehensive error handling
 * - Include detailed logging
 * - Test thoroughly before production use
 * - Follow the established patterns in this template
 */ 