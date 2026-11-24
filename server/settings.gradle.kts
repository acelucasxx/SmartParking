rootProject.name = "smartparking-server"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include(
    "app",
    "common",
    "iot",
    "device",
    "ownership",
    "member",
    "control",
    "notify",
    "admin",
)
