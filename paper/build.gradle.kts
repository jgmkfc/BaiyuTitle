import net.minecrell.pluginyml.paper.PaperPluginDescription
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.shadow) // Shades and relocates dependencies, see https://gradleup.com/shadow/
    alias(libs.plugins.run.paper) // Built in test server using runServer and runMojangMappedServer tasks
    alias(libs.plugins.plugin.yml.bukkit) // Automatic plugin.yml generation
    alias(libs.plugins.plugin.yml.paper) // Automatic plugin.yml generation
}

dependencies {
    // Core dependencies
    implementation(projects.common)
    // PluginLoader 在 Paper libraries 尚未装载前解析 paper-libraries.json，必须随主 JAR 打包。
    implementation(libs.gson)

    // API
    implementation(libs.commandapi.shade.paper)
    implementation(libs.triumph.gui) {
        exclude("net.kyori")
    }
    api(libs.colorparser.paper) {
        exclude("net.kyori")
    }
    api(libs.threadutil.bukkit)

    // Plugin dependencies
    implementation(libs.bstats)
    compileOnly(libs.placeholderapi) {
        exclude("me.clip.placeholderapi.libs", "kyori")
    }

    // Database dependencies - Core
    library(libs.bundles.flyway)

    // Database dependencies - JDBC drivers
    library(libs.bundles.jdbcdrivers)

    // 测试：paper 层只测不依赖运行中服务器的纯逻辑（布局解析、lore 渲染等）
    testImplementation(libs.annotations)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.bundles.junit)
    testRuntimeOnly(libs.slf4j)
    testImplementation(libs.paper.api)
}

tasks {
    // shadowJar and jar otherwise target the same unclassified archive. With parallel
    // execution the thin jar can overwrite the deployable plugin nondeterministically.
    jar {
        enabled = false
    }

    val verifyPluginJar = register("verifyPluginJar") {
        group = "verification"
        description = "Verifies that the deployable plugin JAR contains its embedded modules"
        dependsOn(shadowJar)

        val pluginJar = shadowJar.flatMap { it.archiveFile }
        inputs.file(pluginJar)

        doLast {
            val requiredEntries = listOf(
                "net/baiyu/title/BaiYuTitle.class",
                "net/baiyu/title/BaiYuTitlePluginLoader.class",
                "net/baiyu/title/AbstractBaiYuTitle.class",
                "net/baiyu/title/api/BaiYuTitleAPI.class",
                "net/baiyu/title/shaded/google/Gson.class",
                "paper-plugin.yml",
            )

            ZipFile(pluginJar.get().asFile).use { jar ->
                val missingEntries = requiredEntries.filter { jar.getEntry(it) == null }
                check(missingEntries.isEmpty()) {
                    "Deployable plugin JAR is missing required entries: ${missingEntries.joinToString()}"
                }
            }
        }
    }

    build {
        dependsOn(verifyPluginJar)
    }

    shadowJar {
        archiveClassifier.set("")

        // Shadow classes
        fun reloc(originPkg: String, targetPkg: String) = relocate(originPkg, "${project.relocationPackage}.${targetPkg}")

        reloc("io.github.milkdrinkers.colorparser", "colorparser")
        reloc("io.github.milkdrinkers.threadutil", "threadutil")
        reloc("dev.jorel.commandapi", "commandapi")
        reloc("dev.triumphteam.gui", "triumphgui")
        reloc("com.zaxxer.hikari", "hikaricp")
        reloc("org.bstats", "bstats")

        reloc("io.leangen.geantyref", "geantyref")
        reloc("org.yaml", "yaml")
        reloc("org.spongepowered", "spongepowered")

        reloc("com.google.errorprone", "google")
        reloc("com.google.gson", "google")

        mergeServiceFiles()
    }

    runServer {
        // Configure the Minecraft version for our task.
        minecraftVersion(libs.versions.paper.run.get())

        // IntelliJ IDEA debugger setup: https://docs.papermc.io/paper/dev/debugging#using-a-remote-debugger
        jvmArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005", "-DPaper.IgnoreJavaVersion=true", "-Dcom.mojang.eula.agree=true", "-DIReallyKnowWhatIAmDoingISwear", "-Dpaper.playerconnection.keepalive=6000", "-Dfile.encoding=UTF-8") // UTF-8：插件中文日志在 Windows 控制台不乱码
        systemProperty("terminal.jline", false)
        systemProperty("terminal.ansi", true)

        // Automatically install dependencies
        downloadPlugins {
            github("MilkBowl", "Vault", "1.7.3", "Vault.jar")
            github("PlaceholderAPI", "PlaceholderAPI", "2.12.2", "PlaceholderAPI-2.12.2.jar")
        }
    }
}

bukkit { // Options: https://docs.eldoria.de/pluginyml/bukkit/
    // Plugin main class (required)
    main = rootProject.entryPointClass

    // Plugin Information
    name = rootProject.name
    prefix = rootProject.name
    version = "${rootProject.version}"
    description = "${rootProject.description}"
    authors = rootProject.authors
    contributors = rootProject.contributors
    apiVersion = libs.versions.paper.api.get().substringBefore("-R").substringBefore("-pre")
    foliaSupported = false // 设计文档 §1.6.2：不做 Folia 支持声明

    // Misc properties
    load = net.minecrell.pluginyml.bukkit.BukkitPluginDescription.PluginLoadOrder.POSTWORLD // STARTUP or POSTWORLD
    depend = listOf()
    softDepend = listOf("Vault", "PlaceholderAPI")
    loadBefore = listOf()
    provides = listOf()

    // Commands：/baiyutitle（/byt）由 CommandAPI 注册（Brigadier）；
    // 不得在 plugin.yml 重复声明，否则 Bukkit 包装命令会遮蔽 Brigadier 命令树

    // Permissions
    permissions {
        register("baiyutitle.command") {
            description = "Allows using the BaiYuTitle command"
            default = net.minecrell.pluginyml.bukkit.BukkitPluginDescription.Permission.Default.TRUE
        }
        register("baiyutitle.use") {
            description = "Allows using titles, shop, and warehouse"
            default = net.minecrell.pluginyml.bukkit.BukkitPluginDescription.Permission.Default.TRUE
        }
        register("baiyutitle.admin") {
            description = "Admin access to manage titles"
            default = net.minecrell.pluginyml.bukkit.BukkitPluginDescription.Permission.Default.OP
        }
        register("baiyutitle.command.help") {
            description = "Allows using /byt help"
            default = net.minecrell.pluginyml.bukkit.BukkitPluginDescription.Permission.Default.TRUE
        }
        register("baiyutitle.command.coin.others") {
            description = "Allows checking other players' coin balance"
            default = net.minecrell.pluginyml.bukkit.BukkitPluginDescription.Permission.Default.OP
        }
        register("baiyutitle.trade.create") {
            description = "Allows creating trade offers (without it players can only accept)"
            default = net.minecrell.pluginyml.bukkit.BukkitPluginDescription.Permission.Default.TRUE
        }
        register("baiyutitle.*") {
            description = "All BaiYuTitle permissions"
            default = net.minecrell.pluginyml.bukkit.BukkitPluginDescription.Permission.Default.OP
            children = listOf("baiyutitle.command", "baiyutitle.use", "baiyutitle.admin",
                "baiyutitle.command.help", "baiyutitle.command.coin.others",
                "baiyutitle.trade.create")
        }
    }
}

paper { // Options: https://docs.eldoria.de/pluginyml/paper/
    main = rootProject.entryPointClass
    loader = rootProject.entryPointClass + "PluginLoader"
    generateLibrariesJson = true
    load = net.minecrell.pluginyml.bukkit.BukkitPluginDescription.PluginLoadOrder.POSTWORLD

    // Info
    name = rootProject.name
    prefix = rootProject.name
    version = "${rootProject.version}"
    description = "${rootProject.description}"
    authors = rootProject.authors
    contributors = rootProject.contributors
    apiVersion = libs.versions.paper.api.get().substringBefore("-R").substringBefore("-pre")
    foliaSupported = false // 设计文档 §1.6.2：不做 Folia 支持声明

    // Dependencies
    hasOpenClassloader = true
    bootstrapDependencies {}
    serverDependencies {
        register("Vault") {
            load = PaperPluginDescription.RelativeLoadOrder.BEFORE
            required = false
        }
        register("PlaceholderAPI") {
            load = PaperPluginDescription.RelativeLoadOrder.BEFORE
            required = false
        }
    }
    provides = listOf()

    // Paper 原生插件只读取 paper-plugin.yml；权限必须在此单独注册，不能依赖上面的 Bukkit 描述。
    permissions {
        register("baiyutitle.command") {
            description = "Allows using the BaiYuTitle command"
            default = net.minecrell.pluginyml.bukkit.BukkitPluginDescription.Permission.Default.TRUE
        }
        register("baiyutitle.use") {
            description = "Allows using titles, shop, and warehouse"
            default = net.minecrell.pluginyml.bukkit.BukkitPluginDescription.Permission.Default.TRUE
        }
        register("baiyutitle.admin") {
            description = "Admin access to manage titles"
            default = net.minecrell.pluginyml.bukkit.BukkitPluginDescription.Permission.Default.OP
        }
        register("baiyutitle.command.help") {
            description = "Allows using /byt help"
            default = net.minecrell.pluginyml.bukkit.BukkitPluginDescription.Permission.Default.TRUE
        }
        register("baiyutitle.command.coin.others") {
            description = "Allows checking other players' coin balance"
            default = net.minecrell.pluginyml.bukkit.BukkitPluginDescription.Permission.Default.OP
        }
        register("baiyutitle.trade.create") {
            description = "Allows creating trade offers (without it players can only accept)"
            default = net.minecrell.pluginyml.bukkit.BukkitPluginDescription.Permission.Default.TRUE
        }
        register("baiyutitle.*") {
            description = "All BaiYuTitle permissions"
            default = net.minecrell.pluginyml.bukkit.BukkitPluginDescription.Permission.Default.OP
            children = listOf("baiyutitle.command", "baiyutitle.use", "baiyutitle.admin",
                "baiyutitle.command.help", "baiyutitle.command.coin.others",
                "baiyutitle.trade.create")
        }
    }
}
