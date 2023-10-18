import groovy.json.JsonSlurper
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import com.atlassian.jira.component.ComponentAccessor

String jsonString = '''...''' // Insert your JSON string here

def parsedJson = new JsonSlurper().parseText(jsonString)

// Define the current date and the date 14 days ago
ZonedDateTime currentDate = ZonedDateTime.now()
ZonedDateTime fourteenDaysAgo = currentDate.minusDays(14)

// Define a list to store the users added more than 14 days ago
def usersAddedEarlier = []

// Define a formatter for user-friendly date format
DateTimeFormatter friendlyFormat = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm")

// Get the UserManager service
def userManager = ComponentAccessor.getUserManager()

parsedJson.records.each { record ->
    if (record.objectItem.name == "test-group" && record.summary == "User added to group") {
        ZonedDateTime recordDate = ZonedDateTime.parse(record.created, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        
        if (recordDate.isBefore(fourteenDaysAgo)) {
            // Fetch the active status of the user
            def user = userManager.getUserByName(record.associatedItems[0].name)
            boolean isActive = user ? user.isActive() : false

            def userDetail = [
                user: record.associatedItems[0].name,
                dateAdded: recordDate.format(friendlyFormat), // Format date here
                isActive: isActive
            ]
            usersAddedEarlier << userDetail
        }
    }
}

println(usersAddedEarlier)
