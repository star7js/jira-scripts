# Jira Test Environment Backup

This directory is used to store the `jira-backup.zip` file, which contains the pre-configured state for your reproducible Jira test environment.

## How to Generate the Backup

1.  **Start the Jira Environment:**
    *   Navigate to the `testing/` directory.
    *   Temporarily **comment out** the `COPY` line in `testing/jira/Dockerfile`.
    *   Run `docker-compose up`.

2.  **Initial Jira Setup:**
    *   Open your browser to `http://localhost:8080`.
    *   Complete the Jira setup wizard.
    *   Log in as an administrator.

3.  **Configure Your Environment:**
    *   Install the **ScriptRunner for Jira** app from the Atlassian Marketplace.
    *   Create all the custom fields required by your scripts (e.g., "New Project Or Team Space Name", "Project Type", "Project Lead / Project Admin", etc.).
    *   Create a sample project.
    *   Add any necessary users or groups.

4.  **Create the Backup:**
    *   Go to **Jira Administration > System > Backup system**.
    *   Enter a file name (e.g., `jira-backup`).
    *   Click the **Backup** button.

5.  **Copy the Backup File:**
    *   Once the backup is complete, you need to copy it from the Docker container to your local machine.
    *   Run the following command in your terminal:
        ```sh
        docker cp jira_test_env:/var/atlassian/application-data/jira/export/jira-backup.zip ./testing/jira/import/
        ```
    *   *(Note: The exact filename might differ if you chose a different name in the backup screen.)*

6.  **Finalize:**
    *   **Uncomment** the `COPY` line in `testing/jira/Dockerfile`.
    *   You can now stop the environment (`docker-compose down`).

From now on, whenever you run `docker-compose up` with an empty data volume, it will automatically restore from this backup file, giving you a perfectly clean and configured Jira instance. 