@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.uyatame.ucrt

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import com.uyatame.ucrt.dsre.DsreFileConverter
import com.uyatame.ucrt.dsre.DsreParameters
import com.uyatame.ucrt.data.AppSettings
import com.uyatame.ucrt.data.Keys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

const val TAB_DSRE = 4

private enum class DsrePreset(val titleRes: Int) {
    REFERENCE(R.string.dsre_preset_reference), CLEAR_NARROW(R.string.dsre_preset_clear_narrow),
    NATURAL(R.string.dsre_preset_natural), CUSTOM(R.string.dsre_preset_custom)
}
private enum class DsreOutputFormat { WAV, ALAC, FLAC, MP3 }
private data class DsreValues(val harmonic:Int,val strength:Float,val rate:Int,val width:Float,val dynamic:Float,val context:Float)
private fun presetValues(p:DsrePreset)=when(p){
    DsrePreset.REFERENCE->DsreValues(8,.67f,96000,.67f,.55f,.04f)
    DsrePreset.CLEAR_NARROW->DsreValues(15,.48f,48000,.78f,1.03f,.02f)
    DsrePreset.NATURAL->DsreValues(14,.45f,48000,.98f,1.10f,.04f)
    DsrePreset.CUSTOM->DsreValues(15,.47f,48000,.98f,1.11f,.04f)
}

@Composable fun DsreConverterScreen(vm: MainViewModel, settings: AppSettings){
    val ctx=LocalContext.current
    var uriText by rememberSaveable{mutableStateOf<String?>(null)}
    var fileName by rememberSaveable{mutableStateOf<String?>(null)}
    var preset by rememberSaveable(settings.dsrePreset){mutableStateOf(settings.dsrePreset)}
    var harmonic by rememberSaveable(settings.dsreHarmonic){mutableFloatStateOf(settings.dsreHarmonic)}
    var strength by rememberSaveable(settings.dsreStrength){mutableFloatStateOf(settings.dsreStrength)}
    var rate by rememberSaveable(settings.dsreRate){mutableIntStateOf(settings.dsreRate)}
    var width by rememberSaveable(settings.dsreWidth){mutableFloatStateOf(settings.dsreWidth)}
    var dynamic by rememberSaveable(settings.dsreDynamic){mutableFloatStateOf(settings.dsreDynamic)}
    var context by rememberSaveable(settings.dsreContext){mutableFloatStateOf(settings.dsreContext)}
    var format by rememberSaveable(settings.dsreFormat){mutableStateOf(settings.dsreFormat)}
    var converting by rememberSaveable { mutableStateOf(false) }
    var progress by rememberSaveable { mutableFloatStateOf(0f) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val converter = remember { DsreFileConverter(ctx.applicationContext) }
    val outputPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { out: Uri? ->
        val input = uriText?.let(Uri::parse)
        if (out != null && input != null) {
            converting = true; progress = 0f; error = null; status = S(R.string.dsre_preparing)
            val params = DsreParameters(
                harmonicCount = (1f + harmonic.coerceIn(0f, 1f) * 23f).toInt(),
                strength = 0.10f + strength.coerceIn(0f, 1f) * 0.70f,
                targetSampleRate = rate,
                stereoWidth = 0.50f + width.coerceIn(0f, 1f),
                dynamic = 1.00f + dynamic.coerceIn(0f, 1f) * 0.50f,
                contextSeconds = context.coerceIn(0f, 1f) * 0.10f,
            )
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        converter.convert(input, out, params, format) { p ->
                            scope.launch { progress = p.fraction; status = S(R.string.dsre_converting) }
                        }
                    }
                }.onSuccess { status = S(R.string.dsre_completed) }
                    .onFailure { if (it is InterruptedException) status = S(R.string.dsre_cancelled) else error = it.message ?: it.javaClass.simpleName }
                converting = false
            }
        }
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){u:Uri?->
        if(u!=null){ runCatching{ctx.contentResolver.takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
            uriText=u.toString(); fileName=displayName(ctx,u)?:u.lastPathSegment?:u.toString() }
    }
    fun usePreset(p: DsrePreset) {
        preset = p.name
        val v = presetValues(p)
        harmonic = ((v.harmonic - 1f) / 23f).coerceIn(0f, 1f)
        strength = ((v.strength - 0.10f) / 0.70f).coerceIn(0f, 1f)
        rate = v.rate
        width = (v.width - 0.50f).coerceIn(0f, 1f)
        dynamic = ((v.dynamic - 1.00f) / 0.50f).coerceIn(0f, 1f)
        context = (v.context / 0.10f).coerceIn(0f, 1f)
        vm.set(Keys.dsrePreset, preset)
        vm.set(Keys.dsreHarmonic, harmonic); vm.set(Keys.dsreStrength, strength)
        vm.set(Keys.dsreRate, rate); vm.set(Keys.dsreWidth, width)
        vm.set(Keys.dsreDynamic, dynamic); vm.set(Keys.dsreContext, context)
    }
    fun custom(){ if (preset != DsrePreset.CUSTOM.name) { preset=DsrePreset.CUSTOM.name; vm.set(Keys.dsrePreset, preset) } }
    WideCenter { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        Text(S(R.string.dsre_title),style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
        Text(S(R.string.dsre_description),color=MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text(S(R.string.dsre_input_file),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
            if(fileName==null) Text(S(R.string.dsre_no_file),color=MaterialTheme.colorScheme.onSurfaceVariant) else {Text(fileName!!,maxLines=2,overflow=TextOverflow.Ellipsis);Text(uriText.orEmpty(),style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button({picker.launch(arrayOf("audio/*","application/ogg"))}){Icon(AppIcons.Folder,null);Spacer(Modifier.width(8.dp));Text(S(if(fileName==null)R.string.dsre_select_file else R.string.dsre_change_file))};if(fileName!=null)OutlinedButton({fileName=null;uriText=null}){Text(S(R.string.clear))}}
        }}
        Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            Text(S(R.string.dsre_settings),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
            ChoiceField(S(R.string.dsre_preset),DsrePreset.valueOf(preset),DsrePreset.entries,{S(it.titleRes)}){usePreset(it)}
            NormalizedSlider(S(R.string.dsre_harmonic), harmonic) { harmonic = it; custom(); vm.set(Keys.dsreHarmonic, it) }
            NormalizedSlider(S(R.string.dsre_strength), strength) { strength = it; custom(); vm.set(Keys.dsreStrength, it) }
            ChoiceField(S(R.string.dsre_sample_rate),rate,listOf(44100,48000,88200,96000,192000),{"$it Hz"}){rate=it;custom();vm.set(Keys.dsreRate,it)}
            NormalizedSlider(S(R.string.dsre_stereo_width), width) { width = it; custom(); vm.set(Keys.dsreWidth, it) }
            NormalizedSlider(S(R.string.dsre_dynamic), dynamic) { dynamic = it; custom(); vm.set(Keys.dsreDynamic, it) }
            NormalizedSlider(S(R.string.dsre_context), context) { context = it; custom(); vm.set(Keys.dsreContext, it) }
            HorizontalDivider();ChoiceField(S(R.string.dsre_output_format),DsreOutputFormat.valueOf(format),DsreOutputFormat.entries,{it.name}){format=it.name;vm.set(Keys.dsreFormat,it.name)}
        }}
        Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text(S(R.string.dsre_conversion),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
            if (converting) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(status ?: S(R.string.dsre_converting), color=MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton({ converter.cancel() }, modifier=Modifier.fillMaxWidth()){Text(S(R.string.cancel))}
            } else {
                Text(error ?: status ?: S(if(fileName==null)R.string.dsre_select_before_start else R.string.dsre_ready), color=if(error==null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                Button({
                    val base=(fileName ?: "audio").substringBeforeLast('.').ifBlank { "audio" }
                    val extension = when (DsreOutputFormat.valueOf(format)) {
                        DsreOutputFormat.WAV -> "wav"
                        DsreOutputFormat.ALAC -> "m4a"
                        DsreOutputFormat.FLAC -> "flac"
                        DsreOutputFormat.MP3 -> "mp3"
                    }
                    outputPicker.launch("${base}_DSRE.$extension")
                },enabled=fileName!=null,modifier=Modifier.fillMaxWidth()){Text(S(R.string.dsre_start_conversion))}
            }
        }}
    }}
}
private fun displayName(c:android.content.Context,u:Uri):String?=runCatching{c.contentResolver.query(u,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else null}}.getOrNull()

@Composable private fun <T> ChoiceField(label:String,selected:T,values:List<T>,title:(T)->String,onSelect:(T)->Unit){var open by rememberSaveable{mutableStateOf(false)};ExposedDropdownMenuBox(open,{open=it}){OutlinedTextField(title(selected),{},readOnly=true,label={Text(label)},trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(open)},modifier=Modifier.menuAnchor().fillMaxWidth());ExposedDropdownMenu(open,{open=false}){values.forEach{v->DropdownMenuItem({Text(title(v))},{onSelect(v);open=false})}}}}
@Composable
private fun NormalizedSlider(label: String, value: Float, onValue: (Float) -> Unit) {
    val normalized = value.coerceIn(0f, 1f)
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f))
            Text(String.format(Locale.US, "%.2f", normalized), fontWeight = FontWeight.Bold)
        }
        Slider(
            value = normalized,
            onValueChange = { onValue(it.coerceIn(0f, 1f)) },
            valueRange = 0f..1f,
            steps = 99,
        )
    }
}
