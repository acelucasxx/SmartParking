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

// 真机联调工具（IOT-11）：站在云端一侧对真实 DTU 跑查询 / 抬杆 / 落杆 / 版本，记录时延与原始上行
include("dtu-bench")
project(":dtu-bench").projectDir = file("../tools/dtu-bench")
