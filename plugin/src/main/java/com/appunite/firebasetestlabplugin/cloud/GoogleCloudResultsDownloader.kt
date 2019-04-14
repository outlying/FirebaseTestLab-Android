package com.appunite.firebasetestlabplugin.cloud

import com.appunite.firebasetestlabplugin.FirebaseTestLabPlugin
import com.appunite.firebasetestlabplugin.model.ResultTypes
import com.appunite.firebasetestlabplugin.utils.asCommand
import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import java.io.File


internal class GoogleCloudResultsDownloader(
        private val googleCloudSdk: FirebaseTestLabPlugin.GoogleCloudSdk,
        private val resultsTypes: ResultTypes,
        private val googleCloudDir: File,
        private val resultsDir: File,
        private val googleCloudBucketName: String,
        private val logger: Logger) {

    fun getResults() {
        if (!resultsTypes.junit && !resultsTypes.logcat && !resultsTypes.video && !resultsTypes.xml) return
        
        prepareDownloadDirectory()
        downloadTestResults()
    }

    private fun prepareDownloadDirectory() {
        resultsDir.mkdirs()
        if (!resultsDir.exists()) throw GradleException("Cannot create results dir at: $googleCloudDir")
    }

    private fun downloadTestResults() {
        val excludeQuery = StringBuilder().append("-x \".*\\.txt$|.*\\.apk$")
        if (!resultsTypes.xml) {
            excludeQuery.append("|.*\\.xml$")
        }
        if (!resultsTypes.xml) {
            excludeQuery.append("|.*\\.results$")
        }
        if (!resultsTypes.logcat) {
            excludeQuery.append("|.*\\logcat$")
        }
        if (!resultsTypes.video) {
            excludeQuery.append("|.*\\.mp4$")
        }
        excludeQuery.append("|.*\\.txt$\"").toString()
        val processCreator = ProcessBuilder("""${googleCloudSdk.gsutil.absolutePath} -m rsync $excludeQuery -r gs://$googleCloudBucketName/$googleCloudDir $resultsDir""".asCommand())
        val process = processCreator.start()

        process.errorStream.bufferedReader().forEachLine { logger.lifecycle(it) }
        process.inputStream.bufferedReader().forEachLine { logger.lifecycle(it) }

        process.waitFor()
    }

    fun clearResultsDir() {
        val processCreator = ProcessBuilder("""${googleCloudSdk.gsutil.absolutePath} rm gs://$googleCloudBucketName/$googleCloudDir/**""".asCommand())
        val process = processCreator.start()

        process.errorStream.bufferedReader().forEachLine { logger.lifecycle(it) }
        process.inputStream.bufferedReader().forEachLine { logger.lifecycle(it) }

        process.waitFor()
    }
}