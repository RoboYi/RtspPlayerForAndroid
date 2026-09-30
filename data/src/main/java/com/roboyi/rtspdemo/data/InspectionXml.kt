package com.roboyi.rtspdemo.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Xml
import java.io.File
import java.io.StringWriter
import org.xmlpull.v1.XmlSerializer

object InspectionXml {
    fun hasAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 29) return true
        val uri = InspectionStore.get(context).folderUri()
        return uri.isNotBlank() && isSupportedFolder(Uri.parse(uri)) &&
            context.contentResolver.persistedUriPermissions.any { it.uri.toString() == uri && it.isWritePermission }
    }
    private fun isSupportedFolder(uri: Uri): Boolean = runCatching {
        uri.authority == "com.android.externalstorage.documents" &&
            DocumentsContract.getTreeDocumentId(uri) in listOf("primary:DCIM", "primary:DCIM/RoboYiPlayer")
    }.getOrDefault(false)
    fun acceptFolder(context: Context, uri: Uri) {
        require(isSupportedFolder(uri)) {
            "请选择内部存储中的 DCIM 或 DCIM/RoboYiPlayer 文件夹"
        }
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        InspectionStore.get(context).setFolderUri(uri.toString())
    }
    fun write(context: Context, session: InspectionSession) {
        val relative = session.video.substringBeforeLast('.') + ".xml"
        val content = serialize(session)
        val backup = File(context.filesDir, "inspection-xml/${session.id}.xml")
        backup.parentFile!!.mkdirs(); backup.writeText(content)
        if (Build.VERSION.SDK_INT < 29) {
            val file = File(AppCaptureRepository(context).publicRoot, relative)
            file.parentFile!!.mkdirs(); file.writeText(content); return
        }
        check(hasAccess(context)) { "请先授权 DCIM/RoboYiPlayer 文件夹以保存 XML" }
        val tree = Uri.parse(InspectionStore.get(context).folderUri())
        var parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        if (DocumentsContract.getTreeDocumentId(tree) == "primary:DCIM") parent = child(context, tree, parent, "RoboYiPlayer", DocumentsContract.Document.MIME_TYPE_DIR)
        relative.substringBeforeLast('/').split('/').forEach { parent = child(context, tree, parent, it, DocumentsContract.Document.MIME_TYPE_DIR) }
        val document = child(context, tree, parent, relative.substringAfterLast('/'), "application/xml")
        checkNotNull(context.contentResolver.openOutputStream(document, "wt")).bufferedWriter().use { it.write(content) }
    }
    private fun child(context: Context, tree: Uri, parent: Uri, name: String, mime: String): Uri {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            while (it.moveToNext()) if (it.getString(1) == name) return DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0))
        }
        return checkNotNull(DocumentsContract.createDocument(context.contentResolver, parent, mime, name)) { "无法创建 $name" }
    }
    fun serialize(session: InspectionSession): String {
        val out = StringWriter(); val xml = Xml.newSerializer(); xml.setOutput(out)
        xml.startDocument("UTF-8", true)
        xml.startTag(null, "recording").attribute(null, "schemaVersion", "1").attribute(null, "id", session.id)
            .attribute(null, "state", session.state).attribute(null, "startedAt", session.startedAt.toString())
            .attribute(null, "durationMs", session.durationMs.toString()).attribute(null, "pathBase", "DCIM/RoboYiPlayer")
        xml.startTag(null, "project"); writeInfo(xml, session.project, session.fields["project"].orEmpty())
        xml.startTag(null, "task"); writeInfo(xml, session.task, session.fields["task"].orEmpty())
        xml.startTag(null, "video").attribute(null, "relativePath", session.video).endTag(null, "video")
        xml.startTag(null, "screenshots")
        session.snapshots.forEach { shot ->
            xml.startTag(null, "screenshot").attribute(null, "relativePath", shot.getString("path"))
                .attribute(null, "capturedAt", shot.optLong("capturedAt").toString())
                .attribute(null, "recordingTimeMs", shot.optLong("recordingTimeMs").toString())
            shot.optJSONObject("defect")?.let { defect ->
                xml.startTag(null, "defect").attribute(null, "displaySeconds", shot.optInt("displaySeconds", 5).toString())
                val fs = shot.optJSONArray("defectFields").objects().map { InfoField(it.getString("key"), it.getString("label")) }
                writeInfo(xml, InspectionInfo.from(defect), fs)
                xml.endTag(null, "defect")
            }
            xml.endTag(null, "screenshot")
        }
        xml.endTag(null, "screenshots").endTag(null, "task").endTag(null, "project")
        xml.startTag(null, "watermarkRules")
        session.rules.forEach { (key, rule) ->
            xml.startTag(null, "rule").attribute(null, "field", key)
            val j = rule.json(); j.keys().forEach { k -> xml.attribute(null, k, j.get(k).toString()) }
            xml.endTag(null, "rule")
        }
        xml.endTag(null, "watermarkRules").endTag(null, "recording"); xml.endDocument()
        return out.toString()
    }
    private fun writeInfo(xml: XmlSerializer, info: InspectionInfo?, fields: List<InfoField>) {
        if (info == null) { xml.attribute(null, "selected", "false"); return }
        xml.attribute(null, "id", info.id).attribute(null, "selected", "true")
        fields.forEach { field -> xml.startTag(null, "field").attribute(null, "key", field.key).attribute(null, "name", field.label)
            .text(info.values[field.key].orEmpty()).endTag(null, "field") }
    }
}
