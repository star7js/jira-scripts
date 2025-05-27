import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.user.ApplicationUser
import org.apache.log4j.Logger

// ── Parameters ───────────────────────────────────────────────
def userName          = 'user'                    // ← change or inject from a field/variable
def groupNameToAdd    = 'jira-servicedesk-users'   // ← change or inject from a field/variable
// ─────────────────────────────────────────────────────────────

// Re‑use managers so we only look them up once
def userManager  = ComponentAccessor.userManager
def groupManager = ComponentAccessor.groupManager
def log          = Logger.getLogger('com.example.scriptrunner.addUserToGroup')

// --- Resolve user & group ------------------------------------------------------
ApplicationUser user  = userManager.getUserByName(userName)
def group              = groupManager.getGroup(groupNameToAdd)

// --- Guard clauses -------------------------------------------------------------
if (!user) {
    log.warn "❌ User ‘$userName’ does not exist – aborting."
    return
}

if (!group) {
    log.warn "❌ Group ‘$groupNameToAdd’ does not exist – aborting."
    return
}

if (groupManager.isUserInGroup(user, group)) {
    log.info "ℹ️  $user.displayName already belongs to $group.name – nothing to do."
    return
}

// --- Action --------------------------------------------------------------------
try {
    groupManager.addUserToGroup(user, group)
    log.info "✅ Added ${user.displayName} to group ${group.name}"
} catch (Exception e) {
    log.error "⚠️  Could not add user: ${e.message}", e
}
