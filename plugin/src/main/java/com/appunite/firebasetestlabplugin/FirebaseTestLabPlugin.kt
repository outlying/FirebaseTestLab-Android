package com.appunite.firebasetestlabplugin

import com.android.build.VariantOutput
import com.android.build.gradle.AppExtension
import com.android.build.gradle.api.BaseVariantOutput
import com.android.build.gradle.api.TestVariant
import com.appunite.firebasetestlabplugin.cloud.FirebaseTestLabProcessCreator
import com.appunite.firebasetestlabplugin.cloud.GoogleCloudResultsDownloader
import com.appunite.firebasetestlabplugin.cloud.ProcessData
import com.appunite.firebasetestlabplugin.cloud.TestType
import com.appunite.firebasetestlabplugin.model.Device
import com.appunite.firebasetestlabplugin.model.TestResults
import com.appunite.firebasetestlabplugin.tasks.InstrumentationShardingTask
import com.appunite.firebasetestlabplugin.utils.Constants
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.Exec
import org.gradle.kotlin.dsl.closureOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.Serializable

class FirebaseTestLabPlugin : Plugin<Project> {

    open class HiddenExec : Exec() {
        init {
            standardOutput = ByteArrayOutputStream()
            errorOutput = standardOutput
            isIgnoreExitValue = true

            doLast {
                if (execResult.exitValue != 0) {
                    println(standardOutput.toString())
                    throw GradleException("exec failed; see output above")
                }
            }
        }
    }

    companion object {
        const val GRADLE_METHOD_NAME = "firebaseTestLab"
        const val ANDROID = "android"
        const val ENSURE_GOOGLE_CLOUD_SDK_INSTALLED = "firebaseTestLabEnsureGCloudSdk"
        const val AUTHORIZE_GOOGLE_CLOUD_SDK = "firebaseTestLabAuth"
        const val SETUP_GOOGLE_CLOUD_SDK = "firebaseTestLabSetup"
        const val CONFIGURE_GOOGLE_CLOUD_SDK_PROJECT = "firebaseTestLabSetProject"
        const val taskPrefixDownload = "firebaseTestLabDownload"
        const val taskPrefixExecute = "firebaseTestLabExecute"
    }

    private lateinit var project: Project

    /**
     * Create extension used to configure testing properties, platforms..
     * After that @param[setup] check for required fields validity
     * and throw @param[GradleException] if needed
     */
    override fun apply(project: Project) {
        this.project = project
    
        project.extensions.create(GRADLE_METHOD_NAME, FirebaseTestLabPluginExtension::class.java, project)
        project.afterEvaluate { setup() }
    }

    data class GoogleCloudSdk(val gcloud: File, val gsutil: File): Serializable

    private fun setup() {
        project.extensions.findByType(FirebaseTestLabPluginExtension::class.java)?.apply {
            
            val availableDevices = devices.toList()
            
            val googleCloudSdk = when {
                !cloudSdkPath.isNullOrEmpty() -> ensureGoogleCloudSdkInstalled(cloudSdkPath, project)
                else -> installGoogleCloudSdk(project)
            }
            
            project.tasks.create(AUTHORIZE_GOOGLE_CLOUD_SDK, Exec::class.java) {
                
                group = Constants.FIREBASE_TEST_LAB
                description = "Authorize Google Cloud SDK"

                dependsOn(ENSURE_GOOGLE_CLOUD_SDK_INSTALLED)
                val keyFile = keyFile
                doFirst {
                    keyFile.doesNotExist { throw GradleException("You need to specify keyFile = file(\"key-file.json\") to authorize Google Cloud SDK") }
                }
                commandLine = listOf(googleCloudSdk.gcloud.absolutePath, "auth", "activate-service-account", "--key-file=${keyFile?.absolutePath}")
            }
            
            project.tasks.create(CONFIGURE_GOOGLE_CLOUD_SDK_PROJECT, Exec::class.java) {
                
                group = Constants.FIREBASE_TEST_LAB
                description = "Configure Google Cloud SDK"

                dependsOn(ENSURE_GOOGLE_CLOUD_SDK_INSTALLED)
                doFirst {
                    if (googleProjectId.isNullOrEmpty()) throw GradleException("You need to set googleProjectId=\"your-project-id\"before run")
                }
                commandLine = listOf(googleCloudSdk.gcloud.absolutePath, "config", "set", "project", "$googleProjectId")
            }
            
            project.tasks.create(SETUP_GOOGLE_CLOUD_SDK) {
                
                group = Constants.FIREBASE_TEST_LAB
                description = "Setup and configure google cloud sdk"

                dependsOn(CONFIGURE_GOOGLE_CLOUD_SDK_PROJECT)
                dependsOn(AUTHORIZE_GOOGLE_CLOUD_SDK)
            }


            val downloader: GoogleCloudResultsDownloader? = if (cloudBucketName != null && cloudDirectoryName != null) {
                GoogleCloudResultsDownloader(
                        googleCloudSdk,
                        resultsTypes,
                        File(cloudDirectoryName),
                        File(project.buildDir, cloudDirectoryName),
                        cloudBucketName!!,
                        project.logger
                )
            } else {
                null
            }

            if (clearDirectoryBeforeRun && downloader == null) {
                throw IllegalStateException("If you want to clear directory before run you need to setup cloudBucketName and cloudDirectoryName")
            }

            (project.extensions.findByName(ANDROID) as AppExtension).apply {
                testVariants.toList().forEach { testVariant ->
                    createGroupedTestLabTask(availableDevices, testVariant, ignoreFailures, downloader, googleCloudSdk, cloudBucketName, cloudDirectoryName)
                }
            }


        }
    }

    data class DeviceAppMap(val device: Device, val apk: BaseVariantOutput)

    data class Test(val device: Device, val apk: BaseVariantOutput, val testApk: BaseVariantOutput): Serializable
    
    private fun createGroupedTestLabTask(
        devices: List<Device>,
        variant: TestVariant,
        ignoreFailures: Boolean,
        downloader: GoogleCloudResultsDownloader?,
        sdk: GoogleCloudSdk,
        cloudBucketName: String?,
        cloudDirectoryName: String?
    ) {
        val variantName = variant.testedVariant?.name?.capitalize() ?: ""

        val cleanTask = "firebaseTestLabClean${variantName.capitalize()}"

        val variantSuffix = variantName.capitalize()
        val runTestsTask = taskPrefixExecute + variantSuffix
        val runTestsTaskInstrumentation = "${runTestsTask}Instrumentation"
        val runTestsTaskRobo = "${runTestsTask}Robo"

        if (downloader != null) {
            project.task(cleanTask, closureOf<Task> {
                group = Constants.FIREBASE_TEST_LAB
                description = "Clean test lab artifacts on google storage"
                dependsOn(SETUP_GOOGLE_CLOUD_SDK)
                doLast {
                    downloader.clearResultsDir()
                }
            })
        }

        val appVersions = combineAll(devices, variant.testedVariant.outputs, ::DeviceAppMap)
                .filter {
                    val hasAbiSplits = it.apk.filterTypes.contains(VariantOutput.ABI)
                    if (hasAbiSplits) {
                        if (it.device.filterAbiSplits) {
                            val abi = it.apk.filters.first { it.filterType == VariantOutput.ABI }.identifier
                            it.device.abiSplits.contains(abi)
                        } else {
                            true
                        }
                    } else {
                        it.device.testUniversalApk
                    }
                }
        val roboTasks = appVersions
                .map {
                    test ->
                    val devicePart = test.device.name.capitalize()
                    val apkPart = dashToCamelCase(test.apk.name).capitalize()
                    val taskName = "$runTestsTaskRobo$devicePart$apkPart"
                    project.task(taskName, closureOf<Task> {
                        inputs.files(test.apk.outputFile)
                        group = Constants.FIREBASE_TEST_LAB
                        description = "Run Robo test for ${test.device.name} device on $variantName/${test.apk.name} in Firebase Test Lab"
                        if (downloader != null) {
                            mustRunAfter(cleanTask)
                        }
                        dependsOn(SETUP_GOOGLE_CLOUD_SDK)
                        dependsOn(arrayOf(test.apk.assemble))
                        doLast {
                            val result = FirebaseTestLabProcessCreator.callFirebaseTestLab(ProcessData(
                                sdk = sdk,
                                gCloudBucketName = cloudBucketName,
                                gCloudDirectory = cloudDirectoryName,
                                device = test.device,
                                apk = test.apk.outputFile,
                                
                                testType = TestType.Robo
                            ))
                            processResult(result, ignoreFailures)
                        }
                    })
                }
    
        val testResultFile = File(project.buildDir, "TestResults.txt")
        
        val instrumentationTasks: List<Task> = combineAll(appVersions, variant.outputs)
        { deviceAndMap, testApk -> Test(deviceAndMap.device, deviceAndMap.apk, testApk) }
            .map { test ->
                val devicePart = test.device.name.capitalize()
                val apkPart = dashToCamelCase(test.apk.name).capitalize()
                val testApkPart = test.testApk.let { if (it.filters.isEmpty()) "" else dashToCamelCase(it.name).capitalize() }
                val taskName = "$runTestsTaskInstrumentation$devicePart$apkPart$testApkPart"
                val numShards = test.device.numShards
    
                if (numShards > 0) {
                    project.tasks.create(taskName, InstrumentationShardingTask::class.java) {
                        group = Constants.FIREBASE_TEST_LAB
                        description = "Run Instrumentation test for ${test.device.name} device on $variantName/${test.apk.name} in Firebase Test Lab"
                        this.processData = ProcessData(
                            sdk = sdk,
                            gCloudBucketName = cloudBucketName,
                            gCloudDirectory = cloudDirectoryName,
                            device = test.device,
                            apk = test.apk.outputFile,
                            testType = TestType.Instrumentation(test.testApk.outputFile)
                        )
                        this.stateFile = testResultFile
            
                        if (downloader != null) {
                            mustRunAfter(cleanTask)
                        }
                        dependsOn(SETUP_GOOGLE_CLOUD_SDK)
                        dependsOn(arrayOf(test.apk.assemble, test.testApk.assemble))
    
                        doFirst {
                            testResultFile.writeText("")
                        }
            
                        doLast {
                            val testResults = testResultFile.readText()
                            val resultCode: Int? = testResults.toIntOrNull()
    
                            logger.lifecycle("TESTS RESULTS: Every digit represents single shard.")
                            logger.lifecycle("\"0\" means -> tests for particular shard passed.")
                            logger.lifecycle("\"1\" means -> tests for particular shard failed.")
    
                            logger.lifecycle("RESULTS_CODE: $resultCode")
                            logger.lifecycle("When result code is equal to 0 means that all tests for all shards passed, otherwise some of them failed.")
    
                            if (resultCode != null) {
                                processResult(resultCode, ignoreFailures)
                            }
                        }
                    }
        
                } else {
                    project.task(taskName, closureOf<Task> {
                        inputs.files(test.testApk.outputFile, test.apk.outputFile)
                        group = Constants.FIREBASE_TEST_LAB
                        description = "Run Instrumentation test for ${test.device.name} device on $variantName/${test.apk.name} in Firebase Test Lab"
                        if (downloader != null) {
                            mustRunAfter(cleanTask)
                        }
                        dependsOn(SETUP_GOOGLE_CLOUD_SDK)
                        dependsOn(arrayOf(test.apk.assemble, test.testApk.assemble))
                        doLast {
                            val result = FirebaseTestLabProcessCreator.callFirebaseTestLab(ProcessData(
                                sdk = sdk,
                                gCloudBucketName = cloudBucketName,
                                gCloudDirectory = cloudDirectoryName,
                                device = test.device,
                                apk = test.apk.outputFile,
                                testType = TestType.Instrumentation(test.testApk.outputFile)
                            ))
                            processResult(result, ignoreFailures)
                        }
                    })
                }
            }

        val allInstrumentation: Task = project.task(runTestsTaskInstrumentation, closureOf<Task> {
            group = Constants.FIREBASE_TEST_LAB
            description = "Run all Instrumentation tests for $variantName in Firebase Test Lab"
            dependsOn(instrumentationTasks)

            doFirst {
                if (devices.isEmpty()) throw IllegalStateException("You need to set et least one device in:\n" +
                        "firebaseTestLab {" +
                        "  devices {\n" +
                        "    nexus6 {\n" +
                        "      androidApiLevels = [21]\n" +
                        "      deviceIds = [\"Nexus6\"]\n" +
                        "      locales = [\"en\"]\n" +
                        "    }\n" +
                        "  } " +
                        "}")

                if (instrumentationTasks.isEmpty()) throw IllegalStateException("Nothing match your filter")
            }
        })

        val allRobo: Task = project.task(runTestsTaskRobo, closureOf<Task> {
            group = Constants.FIREBASE_TEST_LAB
            description = "Run all Robo tests for $variantName in Firebase Test Lab"
            dependsOn(roboTasks)

            doFirst {
                if (devices.isEmpty()) throw IllegalStateException("You need to set et least one device in:\n" +
                        "firebaseTestLab {" +
                        "  devices {\n" +
                        "    nexus6 {\n" +
                        "      androidApiLevels = [21]\n" +
                        "      deviceIds = [\"Nexus6\"]\n" +
                        "      locales = [\"en\"]\n" +
                        "    }\n" +
                        "  } " +
                        "}")

                if (roboTasks.isEmpty()) throw IllegalStateException("Nothing match your filter")
            }
        })

        project.task(runTestsTask, closureOf<Task> {
            group = Constants.FIREBASE_TEST_LAB
            description = "Run all tests for $variantName in Firebase Test Lab"
            dependsOn(allRobo, allInstrumentation)
        })
    
        if (downloader != null) {
            listOf(variantSuffix, "${variantSuffix}Instrumentation").map{suffix ->
               project.task(taskPrefixDownload + suffix, closureOf<Task> {
                    group = Constants.FIREBASE_TEST_LAB
                    description = "Run Android Tests in Firebase Test Lab and download artifacts from google storage"
                    dependsOn(SETUP_GOOGLE_CLOUD_SDK)
                    dependsOn(taskPrefixExecute + suffix)
                    mustRunAfter(cleanTask)

                    doLast {
                        downloader.getResults()
                    }
                })
            }
        }
    }
    
    private fun processResult(result: TestResults, ignoreFailures: Boolean) {
        if (result.isSuccessful) {
            project.logger.lifecycle(result.message)
        } else {
            if (ignoreFailures) {
                project.logger.error("Error: ${result.message}")
            } else {
                throw GradleException(result.message)
            }
        }
    }
    
    private fun processResult(resultCode: Int, ignoreFailures: Boolean) =
        if (resultCode == 0) {
            project.logger.lifecycle("SUCCESS: All tests passed.")
        } else {
            if (ignoreFailures) {
                println("FAILURE: Tests failed.")
                project.logger.error("FAILURE: Tests failed.")
            } else {
                throw GradleException("FAILURE: Tests failed.")
            }
        }
    
    private fun installGoogleCloudSdk(project: Project): GoogleCloudSdk {
        val envGoogleCloudInstallDirPath: String = System.getenv(Constants.GOOGLE_CLOUD_INSTALL_DIR)
        val googleCloudFile: File = ensureGoogleCloudInstallDir(envGoogleCloudInstallDirPath, project)
        val googleCloudTargetDir = File(googleCloudFile, Constants.GOOGLE_CLOUD_DIR_NAME)
        val googleCloudBinPath = File(googleCloudTargetDir, Constants.GOOGLE_CLOUD_BIN_DIR)
    
        val gcloud = File(googleCloudBinPath, Constants.GCLOUD)
        val gsutil = File(googleCloudBinPath, Constants.GSUTIL)
    
        project.logger.lifecycle("""
                    Google Cloud SDK: ${googleCloudFile.absolutePath}
                    Google Cloud BIN: ${googleCloudBinPath.absolutePath}
                """.trimIndent())
    
        project.tasks.create(ENSURE_GOOGLE_CLOUD_SDK_INSTALLED, Exec::class.java) {
        
            group = Constants.FIREBASE_TEST_LAB
            description = "Install Google Cloud SDK"
            outputs.files(gcloud, gsutil)
        
            doFirst {
                if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                    throw IllegalStateException("""
                                Fetching gcloud and gsutil is not supported on Windows OS.
                                You need to install it manually. Instructions: https://cloud.google.com/sdk/downloads#windows
                                firebaseTestLab {
                                  googleCloudSdkPath = "path_to_installed_sdk"
                                }
                                """.trimIndent())
                }
            }
            commandLine = listOf("bash", "-c", "rm -r \"${googleCloudTargetDir.absolutePath}\";export CLOUDSDK_CORE_DISABLE_PROMPTS=1 && export CLOUDSDK_INSTALL_DIR=\"${googleCloudFile.absolutePath}\" && curl https://sdk.cloud.google.com | bash")
        
            doLast {
                gcloud.doesNotExist { NullPointerException("gcloud installation failed.") }
                gsutil.doesNotExist { NullPointerException("gsutil installation failed.") }
            }
        }
        return GoogleCloudSdk(gcloud, gsutil)
    }
    
    private fun ensureGoogleCloudInstallDir(env: String?, project: Project): File = when {
        !env.isNullOrEmpty() -> File(env)
        else -> File(project.buildDir, Constants.GCLOUD)
    }
    
    private fun ensureGoogleCloudSdkInstalled(googleCloudSdkPath: String?, project: Project): GoogleCloudSdk {
        val googleCloudSdkFile = File(googleCloudSdkPath)
        val googleCloudFile = File(googleCloudSdkFile, Constants.GCLOUD)
        val googleCloudStorageFile = File(googleCloudSdkFile, Constants.GSUTIL)
    
        project.tasks.create(ENSURE_GOOGLE_CLOUD_SDK_INSTALLED) {
            group = Constants.FIREBASE_TEST_LAB
            description = "Check if Google Cloud SDK is installed and provided."
        
            doFirst {
                googleCloudFile.doesNotExist { throw NullPointerException("gcloud does not exist in path ${googleCloudSdkFile.absoluteFile}") }
                googleCloudStorageFile.doesNotExist { throw NullPointerException("gsutil does not exist in path ${googleCloudSdkFile.absoluteFile}") }
            }
        }
        return GoogleCloudSdk(googleCloudFile, googleCloudStorageFile)
    }
}

private infix fun File?.doesNotExist(action: () -> Unit) = if (this?.exists() != true) action() else Unit

private fun <T1, T2, R> combineAll(l1: Collection<T1>, l2: Collection<T2>, func: (T1, T2) -> R): List<R> =
        l1.flatMap { t1 -> l2.map { t2 -> func(t1, t2)} }

fun dashToCamelCase(dash: String): String =
        dash.split('-', '_').joinToString("") { it.capitalize() }
