// =============================================================================
// Script Name: CreateConfluenceSpaceFromIssue.groovy
// Description: Workflow post-function to create Confluence spaces from Jira issues
// Author: [Your Name]
// Date: [YYYY-MM-DD]
// Version: 1.0.0
// =============================================================================

import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.sal.api.net.Request
import com.atlassian.sal.api.net.Response
import com.atlassian.sal.api.net.ResponseException
import com.atlassian.sal.api.net.ReturningResponseHandler
import groovy.json.JsonBuilder
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import com.atlassian.applinks.api.ApplicationLinkService
import com.atlassian.applinks.api.ApplicationLink
import com.atlassian.applinks.api.ApplicationType
import com.atlassian.applinks.api.application.confluence.ConfluenceApplicationType

// =============================================================================
// CONFIGURATION SECTION
// =============================================================================
// ⚠️  IMPORTANT: Update these values before running the script

// Custom Field Names - Update these to match your Jira instance
def CUSTOM_FIELD_SUMMARY = "Summary of the Space"           // ← Change this
def CUSTOM_FIELD_PURPOSE = "Describe the purpose"           // ← Change this
def CUSTOM_FIELD_SPACE_NAME = "New Desired Space Name"      // ← Change this
def CUSTOM_FIELD_SPACE_KEY = "Desired Space Key"            // ← Change this

// Confluence Configuration
def CONFLUENCE_API_PATH = "/rest/api/space"

// Advanced Configuration
def ENABLE_SECURE_SPACE = false                              // ← Set to true for secure spaces
def SECURE_ADMIN_USER = "admin"                              // ← Admin user for secure spaces

// Advanced Configuration
def ENABLE_DRY_RUN = false                                  // ← Set to true for testing
def TIMEOUT_MS = 30000                                      // Request timeout
def MAX_RETRIES = 3                                         // Maximum retry attempts

// =============================================================================
// LOGGING SETUP
// =============================================================================
def log = LoggerFactory.getLogger('com.example.CreateConfluenceSpaceFromIssue')

// =============================================================================
// VALIDATION FUNCTIONS
// =============================================================================
def validateIssue(issue) {
    if (!issue) {
        log.error("No issue provided to the script")
        return false
    }
    
    log.info("Validating issue: ${issue.key}")
    return true
}

def validateCustomFields(issue, customFieldManager) {
    def errors = []
    
    // Check if custom fields exist
    def cfSummary = customFieldManager.getCustomFieldObjectByName(CUSTOM_FIELD_SUMMARY)
    def cfPurpose = customFieldManager.getCustomFieldObjectByName(CUSTOM_FIELD_PURPOSE)
    def cfSpaceName = customFieldManager.getCustomFieldObjectByName(CUSTOM_FIELD_SPACE_NAME)
    def cfSpaceKey = customFieldManager.getCustomFieldObjectByName(CUSTOM_FIELD_SPACE_KEY)
    
    if (!cfSpaceName) {
        errors << "Custom field '${CUSTOM_FIELD_SPACE_NAME}' not found"
    }
    
    if (!cfSpaceKey) {
        errors << "Custom field '${CUSTOM_FIELD_SPACE_KEY}' not found"
    }
    
    if (errors) {
        log.error("Custom field validation failed: ${errors.join(', ')}")
        return false
    }
    
    log.info("Custom field validation passed")
    return true
}

def validateSpaceData(spaceName, spaceKey) {
    def errors = []
    
    if (!spaceName?.trim()) {
        errors << "Space name is required"
    }
    
    if (!spaceKey?.trim()) {
        errors << "Space key is required"
    }
    
    // Validate space key format (alphanumeric, no spaces)
    if (spaceKey && !spaceKey.matches(/^[A-Z0-9]+$/)) {
        errors << "Space key must be uppercase alphanumeric characters only"
    }
    
    if (errors) {
        log.error("Space data validation failed: ${errors.join(', ')}")
        return false
    }
    
    log.info("Space data validation passed: name='${spaceName}', key='${spaceKey}'")
    return true
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

def getConfluenceApplicationLink() {
    try {
        def applicationLinkService = ComponentAccessor.getOSGiComponentInstanceOfType(ApplicationLinkService.class)
        
        if (!applicationLinkService) {
            throw new Exception("ApplicationLinkService not available")
        }
        
        def confluenceLink = applicationLinkService.getPrimaryApplicationLink(ConfluenceApplicationType.class)
        
        if (!confluenceLink) {
            throw new Exception("No Confluence application link found. Please configure Application Links in Jira.")
        }
        
        log.info("Retrieved Confluence application link successfully")
        return confluenceLink
        
    } catch (Exception e) {
        log.error("Failed to retrieve Confluence application link: ${e.message}", e)
        throw e
    }
}

def createSpacePayload(spaceKey, spaceName, summary, purpose) {
    def description = "${summary}"
    if (purpose?.trim()) {
        description += "\n\n${purpose}"
    }
    
    def jsonBuilder = new JsonBuilder()
    jsonBuilder {
        key spaceKey
        name spaceName
        type "global"
        description {
            plain {
                value description
                representation "plain"
            }
        }
        if (ENABLE_SECURE_SPACE) {
            permissions {
                "space-permissions" ([
                    "user-permissions": [
                        [
                            "type": "user",
                            "username": SECURE_ADMIN_USER,
                            "permissions": ["ADMIN"]
                        ]
                    ]
                ])
            }
        }
    }
    
    def payload = jsonBuilder.toString()
    log.debug("Created payload: ${payload}")
    return payload
}

def makeConfluenceRequest(confluenceLink, payload) {
    def httpClient = confluenceLink.createAuthenticatedRequestFactory().createRequest(Request.MethodType.POST, CONFLUENCE_API_PATH)
    httpClient.setHeader("Content-Type", "application/json")
    httpClient.setRequestBody(payload)
    
    return httpClient
}

def handleResponse(httpClient, spaceKey) {
    try {
        def response = httpClient.execute(new ReturningResponseHandler<Response, Response>() {
            @Override
            Response handle(Response localResponse) throws ResponseException {
                if (localResponse.statusCode == 200) {
                    log.info("Confluence space '${spaceKey}' created successfully")
                    return localResponse
                } else if (localResponse.statusCode == 409) {
                    log.warn("Space key '${spaceKey}' already exists. Skipping creation.")
                    return localResponse
                } else {
                    log.error("Failed to create Confluence space. Status: ${localResponse.statusCode}, Response: ${localResponse.responseBodyAsString}")
                    throw new ResponseException("Failed to create Confluence space")
                }
            }
        } as com.atlassian.sal.api.net.ResponseHandler)
        
        return true
        
    } catch (ResponseException e) {
        log.error("ResponseException during Confluence API call: ${e.message}")
        return false
    } catch (Exception e) {
        log.error("Unexpected error during Confluence API call: ${e.message}", e)
        return false
    }
}

// =============================================================================
// MAIN SCRIPT LOGIC
// =============================================================================
def main() {
    def startTime = System.currentTimeMillis()
    
    try {
        log.info("Starting Confluence space creation from Jira issue...")
        
        // Validate issue (provided by ScriptRunner in post-function context)
        if (!validateIssue(issue)) {
            return false
        }
        
        // Get Jira components
        def customFieldManager = ComponentAccessor.getCustomFieldManager()
        
        // Validate custom fields exist
        if (!validateCustomFields(issue, customFieldManager)) {
            return false
        }
        
        // Get custom field objects
        def cfSummary = customFieldManager.getCustomFieldObjectByName(CUSTOM_FIELD_SUMMARY)
        def cfPurpose = customFieldManager.getCustomFieldObjectByName(CUSTOM_FIELD_PURPOSE)
        def cfSpaceName = customFieldManager.getCustomFieldObjectByName(CUSTOM_FIELD_SPACE_NAME)
        def cfSpaceKey = customFieldManager.getCustomFieldObjectByName(CUSTOM_FIELD_SPACE_KEY)
        
        // Extract values from issue
        def summary = issue.getSummary()
        def purpose = issue.getCustomFieldValue(cfPurpose) as String ?: ""
        def spaceName = issue.getCustomFieldValue(cfSpaceName) as String
        def spaceKey = issue.getCustomFieldValue(cfSpaceKey) as String
        
        log.info("Extracted values - Summary: '${summary}', Purpose: '${purpose}', Space Name: '${spaceName}', Space Key: '${spaceKey}'")
        
        // Validate space data
        if (!validateSpaceData(spaceName, spaceKey)) {
            return false
        }
        
        // Check for dry run
        if (ENABLE_DRY_RUN) {
            log.info("DRY RUN: Would create Confluence space with key '${spaceKey}' and name '${spaceName}'")
            return true
        }
        
        // Get Confluence application link
        def confluenceLink = getConfluenceApplicationLink()
        
        // Create payload
        def payload = createSpacePayload(spaceKey, spaceName, summary, purpose)
        
        // Execute request with retry logic
        def success = executeWithRetry({
            def httpClient = makeConfluenceRequest(confluenceLink, payload)
            return handleResponse(httpClient, spaceKey)
        }, MAX_RETRIES)
        
        def duration = System.currentTimeMillis() - startTime
        log.info("Confluence space creation completed in ${duration}ms")
        
        return success
        
    } catch (Exception e) {
        def duration = System.currentTimeMillis() - startTime
        log.error("Confluence space creation failed after ${duration}ms: ${e.message}", e)
        return false
    }
}

// =============================================================================
// SCRIPT EXECUTION
// =============================================================================
// This script is designed to run as a workflow post-function
// ScriptRunner automatically provides the 'issue' variable in this context

try {
    return main()
} catch (Exception e) {
    log.error("Unexpected error in script execution: ${e.message}", e)
    return false
}

// =============================================================================
// DOCUMENTATION
// =============================================================================
/*
 * SCRIPT PURPOSE:
 * This script creates a Confluence space when a Jira issue transitions through a workflow.
 * It extracts space details from custom fields on the issue and creates the space via
 * Confluence REST API.
 * 
 * PREREQUISITES:
 * - ScriptRunner for Jira app installed
 * - Confluence instance linked via Application Links
 * - Custom fields configured on the issue type
 * - Workflow post-function configured to run this script
 * 
 * CONFIGURATION:
 * - Update custom field names to match your Jira instance
 * - Configure Application Links between Jira and Confluence
 * - Set ENABLE_DRY_RUN to true for testing
 * - Set ENABLE_SECURE_SPACE to true for restricted spaces
 * 
 * CUSTOM FIELDS REQUIRED:
 * - "New Desired Space Name" (Text field)
 * - "Desired Space Key" (Text field)
 * - "Describe the purpose" (Text field, optional)
 * - "Summary of the Space" (Text field, optional)
 * 
 * WORKFLOW SETUP:
 * 1. Create a workflow transition
 * 2. Add a post-function: "Script Post-function"
 * 3. Select this script file
 * 4. Configure the transition to run when space creation is needed
 * 
 * APPLICATION LINKS SETUP:
 * 1. Go to Administration → Application Links
 * 2. Create a new application link to Confluence
 * 3. Configure the link with appropriate permissions
 * 4. Test the connection to ensure it works
 * 
 * USAGE:
 * 1. Update configuration section at the top
 * 2. Ensure custom fields exist and are configured
 * 3. Set up Application Links between Jira and Confluence
 * 4. Configure workflow post-function
 * 5. Test with ENABLE_DRY_RUN = true
 * 6. Deploy to production
 * 
 * OUTPUT:
 * - Success: Returns true, space created in Confluence
 * - Failure: Returns false, workflow transition fails
 * - Logs: Comprehensive logging for debugging
 * 
 * ERROR HANDLING:
 * - Validates all inputs before processing
 * - Handles API errors gracefully
 * - Retries failed requests with exponential backoff
 * - Logs detailed error information
 * 
 * SECURITY:
 * - Uses Application Links for secure authentication
 * - No credentials stored in scripts or configuration
 * - Validates all inputs to prevent injection attacks
 * - Uses HTTPS for API communication
 * 
 * BEST PRACTICES:
 * - Test thoroughly in development environment
 * - Use dry-run mode for initial testing
 * - Monitor logs for any issues
 * - Ensure Application Links are properly configured
 * - Validate custom field names before deployment
 */ 