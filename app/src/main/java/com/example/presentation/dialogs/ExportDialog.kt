package com.example.presentation.dialogs

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.core.model.StickerItem
import com.example.core.model.StickerPack
import com.example.telegram.TelegramExporter
import java.io.File

@Composable
fun ExportDialog(
    pack: StickerPack,
    stickers: List<StickerItem>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .clip(RoundedCornerShape(24.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "تصدير حزمة الملصقات",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "${pack.name} (${stickers.count { it.conversionStatus.name == "SUCCESS" }} ملصق جاهز)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Option 1: Official Telegram Sticker Pack Import Intent
                Button(
                    onClick = {
                        val intent = TelegramExporter.createTelegramImportIntent(context, pack, stickers)
                        if (intent != null) {
                            try {
                                context.startActivity(intent)
                                onDismiss()
                            } catch (_: Exception) {
                                Toast.makeText(
                                    context,
                                    "تطبيق تيليجرام غير مثبت أو لا يدعم استيراد الحزم المباشر حاليًا",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        } else {
                            Toast.makeText(context, "لا توجد ملصقات مكتملة التحويل بعد", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("export_telegram_intent_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("استيراد مباشر إلى تيليجرام (رسمي)")
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Option 2: Export as ZIP
                Button(
                    onClick = {
                        val exportDir = File(context.cacheDir, "exports")
                        if (!exportDir.exists()) exportDir.mkdirs()
                        val safePackName = pack.name.replace("[^\\w\\p{L}-]".toRegex(), "_")
                        val zipFile = File(exportDir, "Telegram_Stickers_${safePackName}.zip")

                        val success = TelegramExporter.buildTelegramStickersZip(context, pack, stickers, zipFile)
                        if (success && zipFile.exists()) {
                            val shareIntent = TelegramExporter.createShareZipIntent(context, zipFile)
                            context.startActivity(android.content.Intent.createChooser(shareIntent, "مشاركة أرشيف الملصقات"))
                            onDismiss()
                        } else {
                            Toast.makeText(context, "فشل إنشاء ملف الأرشيف المضغوط", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("export_zip_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("حفظ ومشاركة كملف ZIP")
                }

                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "💡 نصيحة: يمكنك أيضًا إرسال ملفات الملصقات (.webm أو .webp) مباشرة إلى بوت تيليجرام الرسمي @Stickers لإنشاء حزمة جديدة أو إضافتها إلى حزمة قائمة.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("close_export_dialog"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("إغلاق")
                }
            }
        }
    }
}
