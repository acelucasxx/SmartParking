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

// DTU 模拟器在仓库根的 tools/ 下，作为子工程纳入构建，直接复用 :iot 的编解码
include("dtu-sim")
project(":dtu-sim").projectDir = file("../tools/dtu-sim")
