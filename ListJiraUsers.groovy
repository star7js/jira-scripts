import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.security.login.LoginManager
import com.atlassian.jira.user.ApplicationUser 
import com.atlassian.jira.user.util.UserUtil
import java.text.SimpleDateFormat
import java.util.Date

// --- Component Access ---
// Get components once
def userUtil = ComponentAccessor.getUserUtil()
def loginManager = ComponentAccessor.getComponentOfType(LoginManager.class)
def allUsers = userUtil.getUsers()

// --- Date Formatter ---
// Use HH for 24-hour format if preferred.
def dateFormat = new SimpleDateFormat("dd/MM/yy HH:mm")

// --- HTML Builder ---
StringBuilder htmlBuilder = new StringBuilder()

// --- Table Header ---
htmlBuilder.append("<table border='1' style='border-collapse: collapse;'>") 
htmlBuilder.append("<thead>")
htmlBuilder.append("<tr>")
htmlBuilder.append("<th>User Name</th>")
htmlBuilder.append("<th>Full Name</th>")
htmlBuilder.append("<th>Email Address</th>")
htmlBuilder.append("<th>Last Login</th>")
htmlBuilder.append("<th>Status</th>")
htmlBuilder.append("<th>Groups</th>") 
htmlBuilder.append("</tr>")
htmlBuilder.append("</thead>")
htmlBuilder.append("<tbody>")

// --- User Iteration ---
allUsers.each { ApplicationUser user ->
    String username = user.username
    String displayName = user.displayName
    String emailAddress = user.emailAddress
    boolean isActive = user.active

    String statusText = isActive ? "Active" : "Inactive"
    String lastLoginText
    String groupsText

    if (!isActive) {
        lastLoginText = "N/A (User Inactive)"
        // For inactive users, group info might still be relevant or you can mark it N/A
        Collection<String> groupNames = userUtil.getGroupNamesForUser(username)
        groupsText = groupNames.isEmpty() ? "No Groups" : groupNames.join(", ")
        // Or: groupsText = "N/A (User Inactive)" if you don't want to show groups for inactive users
    } else {
        // Get Login Info (handle null loginInfo)
        def loginInfo = loginManager.getLoginInfo(username)
        Long lastLoginTime = loginInfo?.getLastLoginTime() 

        if (lastLoginTime == null) {
            lastLoginText = "Never logged in"
        } else {
            lastLoginText = dateFormat.format(new Date(lastLoginTime))
        }

        // Get Groups
        Collection<String> groupNames = userUtil.getGroupNamesForUser(username)
        groupsText = groupNames.isEmpty() ? "No Groups" : groupNames.join(", ")
    }

    // --- Append Row using GString for readability ---
    htmlBuilder.append("<tr>")
    htmlBuilder.append("<td>${username}</td>")
    htmlBuilder.append("<td>${displayName}</td>")
    htmlBuilder.append("<td>${emailAddress}</td>")
    htmlBuilder.append("<td>${lastLoginText}</td>")
    htmlBuilder.append("<td>${statusText}</td>")
    htmlBuilder.append("<td>${groupsText}</td>")
    htmlBuilder.append("</tr>")
}

htmlBuilder.append("</tbody>")
htmlBuilder.append("</table>")

return htmlBuilder.toString()
