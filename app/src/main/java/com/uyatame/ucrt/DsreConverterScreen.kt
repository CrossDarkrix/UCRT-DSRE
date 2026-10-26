@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.uyatame.ucrt

import android.content.Context
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.uyatame.ucrt.data.AppSettings
import com.uyatame.ucrt.data.Keys
import com.uyatame.ucrt.dsre.DsreConversionCoordinator
import com.uyatame.ucrt.dsre.DsreServiceJob
import com.uyatame.ucrt.dsre.DsreServiceRequest
import com.uyatame.ucrt.dsre.DsreParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

const val TAB_DSRE = 4

private enum class DsrePreset(val titleRes: Int) {
    REFERENCE(R.string.dsre_preset_reference), CLEAR_NARROW(R.string.dsre_preset_clear_narrow),
    NATURAL(R.string.dsre_preset_natural), CUSTOM(R.string.dsre_preset_custom)
}
private enum class DsreOutputFormat { WAV, ALAC, FLAC, MP3 }
private enum class DsreJobState { WAITING, PROCESSING, COMPLETED, FAILED, CANCELLED }
private data class DsreValues(val harmonic:Int,val strength:Float,val rate:Int,val width:Float,val dynamic:Float,val context:Float)
private data class DsreUserPreset(val name:String,val harmonic:Float,val strength:Float,val rate:Int,val width:Float,val dynamic:Float,val context:Float,val format:String)
private data class DsreBatchJob(
    val uri: Uri,
    val name: String,
    val state: DsreJobState = DsreJobState.WAITING,
    val progress: Float = 0f,
    val stage: String = "",
    val error: String? = null,
)
private fun presetValues(p:DsrePreset)=when(p){
    DsrePreset.REFERENCE->DsreValues(8,.67f,96000,.67f,.55f,.04f)
    DsrePreset.CLEAR_NARROW->DsreValues(15,.48f,48000,.78f,1.03f,.02f)
    DsrePreset.NATURAL->DsreValues(14,.45f,48000,.98f,1.10f,.04f)
    DsrePreset.CUSTOM->DsreValues(15,.47f,48000,.98f,1.11f,.04f)
}

@Composable fun DsreConverterScreen(vm: MainViewModel, settings: AppSettings){
    val ctx=LocalContext.current
    val app=ctx.applicationContext
    val japanese=remember { Locale.getDefault().language == "ja" }
    fun tr(ja:String,en:String)=if(japanese)ja else en
    val prefs=remember { app.getSharedPreferences("dsre_batch",Context.MODE_PRIVATE) }
    var userPresets by remember { mutableStateOf(loadUserPresets(prefs.getString("user_presets", "[]").orEmpty())) }
    var selectedUserPreset by rememberSaveable { mutableStateOf("") }
    var presetNameInput by rememberSaveable { mutableStateOf("") }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var outputTreeText by rememberSaveable { mutableStateOf(prefs.getString("output_tree_uri","").orEmpty()) }
    var outputName by rememberSaveable { mutableStateOf(prefs.getString("output_tree_name","").orEmpty()) }
    var preset by rememberSaveable(settings.dsrePreset){mutableStateOf(settings.dsrePreset)}
    var harmonic by rememberSaveable(settings.dsreHarmonic){mutableFloatStateOf(settings.dsreHarmonic)}
    var strength by rememberSaveable(settings.dsreStrength){mutableFloatStateOf(settings.dsreStrength)}
    var rate by rememberSaveable(settings.dsreRate){mutableIntStateOf(settings.dsreRate)}
    var width by rememberSaveable(settings.dsreWidth){mutableFloatStateOf(settings.dsreWidth)}
    var dynamic by rememberSaveable(settings.dsreDynamic){mutableFloatStateOf(settings.dsreDynamic)}
    var context by rememberSaveable(settings.dsreContext){mutableFloatStateOf(settings.dsreContext)}
    var format by rememberSaveable(settings.dsreFormat){mutableStateOf(settings.dsreFormat)}
    val jobs=remember { mutableStateListOf<DsreBatchJob>() }
    val serviceState by DsreConversionCoordinator.state.collectAsState()
    val converting = serviceState.running
    var batchMessage by remember { mutableStateOf<String?>(null) }
    var batchError by remember { mutableStateOf<String?>(null) }
    val scope=rememberCoroutineScope()

    fun persistRead(uri:Uri){
        runCatching { app.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    fun addInputs(items:List<Pair<Uri,String>>){
        val known=jobs.mapTo(HashSet()){it.uri.toString()}
        items.forEach { (uri,name) -> if(known.add(uri.toString())) jobs.add(DsreBatchJob(uri,name)) }
        batchMessage=tr("${items.size}件を確認し、キューは${jobs.size}件です","Checked ${items.size} item(s); queue has ${jobs.size}")
        batchError=null
    }
    val outputFolderPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->
        if(uri!=null){
            val flags=Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { app.contentResolver.takePersistableUriPermission(uri,flags) }
            val dir=DocumentFile.fromTreeUri(app,uri)
            if(dir!=null && dir.exists() && dir.isDirectory && dir.canWrite()){
                outputTreeText=uri.toString();outputName=dir.name?:uri.lastPathSegment.orEmpty()
                prefs.edit().putString("output_tree_uri",outputTreeText).putString("output_tree_name",outputName).apply()
                batchMessage=tr("保存先を設定しました","Output folder saved");batchError=null
            }else batchError=tr("選択したフォルダへ書き込めません","The selected folder is not writable")
        }
    }
    val filesPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->
        uris.forEach(::persistRead)
        addInputs(uris.map { it to (displayName(app,it)?:it.lastPathSegment?:"audio") })
    }
    val folderPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->
        if(uri!=null){
            persistRead(uri)
            scope.launch{
                val found=withContext(Dispatchers.IO){ collectAudioDocuments(DocumentFile.fromTreeUri(app,uri)) }
                addInputs(found.map { it.uri to (it.name?:"audio") })
                if(found.isEmpty())batchError=tr("音声ファイルが見つかりませんでした","No supported audio files were found")
            }
        }
    }

    LaunchedEffect(serviceState) {
        if (serviceState.jobs.isNotEmpty()) {
            val incoming = serviceState.jobs.map { remote ->
                DsreBatchJob(
                    uri = Uri.parse(remote.uri),
                    name = remote.name,
                    state = runCatching { DsreJobState.valueOf(remote.state) }.getOrDefault(DsreJobState.WAITING),
                    progress = remote.progress,
                    stage = remote.stage,
                    error = remote.error,
                )
            }
            jobs.clear()
            jobs.addAll(incoming)
            batchMessage = serviceState.message
        }
    }

    fun params()=DsreParameters(
        harmonicCount=(1f+harmonic.coerceIn(0f,1f)*23f).toInt(),
        strength=0.10f+strength.coerceIn(0f,1f)*0.70f,
        targetSampleRate=rate,
        stereoWidth=0.50f+width.coerceIn(0f,1f),
        dynamic=1.00f+dynamic.coerceIn(0f,1f)*0.50f,
        contextSeconds=context.coerceIn(0f,1f)*0.10f,
    )
    fun startBatch(retryFailed:Boolean=false){
        if(converting)return
        val treeUri=outputTreeText.takeIf{it.isNotBlank()}?.let(Uri::parse)
        val dir=treeUri?.let{DocumentFile.fromTreeUri(app,it)}
        if(dir==null||!dir.exists()||!dir.isDirectory||!dir.canWrite()){
            batchError=tr("設定した保存先へアクセスできません。保存先を選び直してください。","The saved output folder is unavailable. Select it again.");return
        }
        val selected=jobs.filter { if(retryFailed) it.state==DsreJobState.FAILED else it.state in setOf(DsreJobState.WAITING,DsreJobState.FAILED,DsreJobState.CANCELLED) }
        if(selected.isEmpty()){batchError=tr("変換対象がありません","There are no items to convert");return}
        val request=DsreServiceRequest(
            jobs=selected.map{DsreServiceJob(it.uri.toString(),it.name)},
            outputTree=treeUri.toString(),
            parameters=params(),
            format=format,
        )
        if(DsreConversionCoordinator.start(app,request)){
            batchError=null
            batchMessage=tr("バックグラウンド変換を開始しました","Background conversion started")
        }else batchError=tr("別のDSRE変換が実行中です","Another DSRE conversion is running")
    }

    fun saveUserPreset(nameRaw:String){
        val name=nameRaw.trim()
        if(name.isBlank()){batchError=tr("プリセット名を入力してください","Enter a preset name");return}
        val item=DsreUserPreset(name,harmonic,strength,rate,width,dynamic,context,format)
        userPresets=(userPresets.filterNot{it.name.equals(name,true)}+item).sortedBy{it.name.lowercase(Locale.getDefault())}
        prefs.edit().putString("user_presets",encodeUserPresets(userPresets)).apply()
        selectedUserPreset=name;presetNameInput=name;showSavePresetDialog=false
        batchMessage=tr("プリセット「$name」を保存しました","Saved preset: $name");batchError=null
    }
    fun applyUserPreset(item:DsreUserPreset){
        selectedUserPreset=item.name;presetNameInput=item.name;preset=DsrePreset.CUSTOM.name
        harmonic=item.harmonic.coerceIn(0f,1f);strength=item.strength.coerceIn(0f,1f);rate=item.rate
        width=item.width.coerceIn(0f,1f);dynamic=item.dynamic.coerceIn(0f,1f);context=item.context.coerceIn(0f,1f);format=item.format
        vm.set(Keys.dsrePreset,preset);vm.set(Keys.dsreHarmonic,harmonic);vm.set(Keys.dsreStrength,strength);vm.set(Keys.dsreRate,rate);vm.set(Keys.dsreWidth,width);vm.set(Keys.dsreDynamic,dynamic);vm.set(Keys.dsreContext,context);vm.set(Keys.dsreFormat,format)
        batchMessage=tr("プリセット「${item.name}」を適用しました","Applied preset: ${item.name}")
    }
    fun deleteUserPreset(){
        val name=selectedUserPreset
        if(name.isBlank())return
        userPresets=userPresets.filterNot{it.name==name};prefs.edit().putString("user_presets",encodeUserPresets(userPresets)).apply()
        selectedUserPreset="";presetNameInput="";batchMessage=tr("保存プリセットを削除しました","Saved preset deleted")
    }
    fun usePreset(p:DsrePreset){
        preset=p.name;val v=presetValues(p)
        harmonic=((v.harmonic-1f)/23f).coerceIn(0f,1f);strength=((v.strength-.10f)/.70f).coerceIn(0f,1f)
        rate=v.rate;width=(v.width-.50f).coerceIn(0f,1f);dynamic=((v.dynamic-1f)/.50f).coerceIn(0f,1f);context=(v.context/.10f).coerceIn(0f,1f)
        vm.set(Keys.dsrePreset,preset);vm.set(Keys.dsreHarmonic,harmonic);vm.set(Keys.dsreStrength,strength);vm.set(Keys.dsreRate,rate);vm.set(Keys.dsreWidth,width);vm.set(Keys.dsreDynamic,dynamic);vm.set(Keys.dsreContext,context)
    }
    fun custom(){selectedUserPreset="";if(preset!=DsrePreset.CUSTOM.name){preset=DsrePreset.CUSTOM.name;vm.set(Keys.dsrePreset,preset)}}
    val completed=jobs.count{it.state==DsreJobState.COMPLETED};val failed=jobs.count{it.state==DsreJobState.FAILED}
    val aggregateProgress=if(jobs.isEmpty())0f else jobs.sumOf{when(it.state){DsreJobState.COMPLETED->1.0;DsreJobState.PROCESSING->it.progress.toDouble();else->0.0}}.toFloat()/jobs.size

    if(showSavePresetDialog) AlertDialog(
        onDismissRequest={showSavePresetDialog=false},
        title={Text(tr("プリセットを保存","Save preset"))},
        text={OutlinedTextField(presetNameInput,{presetNameInput=it},singleLine=true,label={Text(tr("プリセット名","Preset name"))},modifier=Modifier.fillMaxWidth())},
        confirmButton={TextButton({saveUserPreset(presetNameInput)}){Text(tr("保存","Save"))}},
        dismissButton={TextButton({showSavePresetDialog=false}){Text(tr("キャンセル","Cancel"))}},
    )
    WideCenter{Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
        Text(S(R.string.dsre_title),style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
        Text(S(R.string.dsre_description),color=MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text(tr("保存先設定","Output settings"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
            Text(if(outputTreeText.isBlank())tr("保存先が設定されていません","Output folder is not set") else tr("保存先: $outputName","Output: $outputName"),maxLines=2,overflow=TextOverflow.Ellipsis)
            OutlinedButton({outputFolderPicker.launch(outputTreeText.takeIf{it.isNotBlank()}?.let(Uri::parse))},enabled=!converting,modifier=Modifier.fillMaxWidth()){Text(if(outputTreeText.isBlank())tr("保存先を選択","Select output folder") else tr("保存先を変更","Change output folder"))}
        }}
        Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text(tr("変換キュー","Conversion queue"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton({filesPicker.launch(arrayOf("audio/*","application/octet-stream"))},enabled=!converting,modifier=Modifier.weight(1f)){Text(tr("ファイル追加","Add files"))}
                OutlinedButton({folderPicker.launch(null)},enabled=!converting,modifier=Modifier.weight(1f)){Text(tr("フォルダ追加","Add folder"))}
            }
            OutlinedButton({jobs.clear();batchMessage=null;batchError=null},enabled=!converting&&jobs.isNotEmpty(),modifier=Modifier.fillMaxWidth()){Text(tr("一覧を消去","Clear queue"))}
            Text(tr("登録 ${jobs.size}件 / 完了 ${completed}件 / 失敗 ${failed}件","Queued ${jobs.size} / Completed $completed / Failed $failed"))
            LinearProgressIndicator({aggregateProgress},Modifier.fillMaxWidth())
            jobs.forEachIndexed{index,job->
                val mark=when(job.state){DsreJobState.WAITING->"○";DsreJobState.PROCESSING->"▶";DsreJobState.COMPLETED->"✓";DsreJobState.FAILED->"!";DsreJobState.CANCELLED->"×"}
                Column(Modifier.fillMaxWidth()){
                    Row(Modifier.fillMaxWidth()){Text("$mark ${job.name}",Modifier.weight(1f),maxLines=1,overflow=TextOverflow.Ellipsis);if(!converting)TextButton({jobs.removeAt(index)}){Text(tr("削除","Remove"))}}
                    if(job.state==DsreJobState.PROCESSING)LinearProgressIndicator({job.progress},Modifier.fillMaxWidth())
                    if(job.stage.isNotBlank())Text(job.stage,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    job.error?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)}
                }
            }
        }}
        Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text(S(R.string.dsre_settings),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
            if(userPresets.isNotEmpty()) ChoiceField(tr("保存プリセット","Saved preset"),selectedUserPreset,userPresets.map{it.name},{it.ifBlank{tr("選択してください","Select")}}){name->userPresets.firstOrNull{it.name==name}?.let(::applyUserPreset)}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton({presetNameInput=selectedUserPreset;showSavePresetDialog=true},modifier=Modifier.weight(1f)){Text(tr("現在値を保存","Save current"))}
                OutlinedButton({deleteUserPreset()},enabled=selectedUserPreset.isNotBlank(),modifier=Modifier.weight(1f)){Text(tr("保存プリセット削除","Delete saved"))}
            }
            ChoiceField(S(R.string.dsre_preset),DsrePreset.valueOf(preset),DsrePreset.entries.toList(),{S(it.titleRes)}){usePreset(it)}
            NormalizedSlider(S(R.string.dsre_harmonic),harmonic){harmonic=it;custom();vm.set(Keys.dsreHarmonic,it)}
            NormalizedSlider(S(R.string.dsre_strength),strength){strength=it;custom();vm.set(Keys.dsreStrength,it)}
            ChoiceField(S(R.string.dsre_sample_rate),rate,listOf(44100,48000,88200,96000,176400,192000),{"${it/1000.0} kHz"}){rate=it;custom();vm.set(Keys.dsreRate,it)}
            NormalizedSlider(S(R.string.dsre_stereo_width),width){width=it;custom();vm.set(Keys.dsreWidth,it)}
            NormalizedSlider(S(R.string.dsre_dynamic),dynamic){dynamic=it;custom();vm.set(Keys.dsreDynamic,it)}
            NormalizedSlider(S(R.string.dsre_context),context){context=it;custom();vm.set(Keys.dsreContext,it)}
            ChoiceField(S(R.string.dsre_output_format),DsreOutputFormat.valueOf(format),DsreOutputFormat.entries.toList(),{it.name}){format=it.name;vm.set(Keys.dsreFormat,format)}
        }}
        Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            batchMessage?.let{Text(it,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            batchError?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            if(converting)Button({DsreConversionCoordinator.cancel(app)},Modifier.fillMaxWidth()){Text(tr("一括変換をキャンセル","Cancel batch"))}
            else{
                Button({startBatch(false)},enabled=jobs.isNotEmpty()&&outputTreeText.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text(tr("一括変換を開始","Start batch conversion"))}
                OutlinedButton({startBatch(true)},enabled=failed>0,modifier=Modifier.fillMaxWidth()){Text(tr("失敗項目を再試行","Retry failed items"))}
            }
        }}
    }}
}

private fun loadUserPresets(raw: String): List<DsreUserPreset> = runCatching {
    val a=JSONArray(raw);List(a.length()){i->val o=a.getJSONObject(i);DsreUserPreset(o.getString("name"),o.getDouble("harmonic").toFloat(),o.getDouble("strength").toFloat(),o.getInt("rate"),o.getDouble("width").toFloat(),o.getDouble("dynamic").toFloat(),o.getDouble("context").toFloat(),o.optString("format","FLAC"))}
}.getOrElse { emptyList() }
private fun encodeUserPresets(items:List<DsreUserPreset>):String{val a=JSONArray();items.forEach{p->a.put(JSONObject().put("name",p.name).put("harmonic",p.harmonic.toDouble()).put("strength",p.strength.toDouble()).put("rate",p.rate).put("width",p.width.toDouble()).put("dynamic",p.dynamic.toDouble()).put("context",p.context.toDouble()).put("format",p.format))};return a.toString()}
private val supportedAudioExtensions=setOf("wav","flac","m4a","mp4","mp3","aac","aif","aiff","wv","ape","ogg","opus")
private fun collectAudioDocuments(root:DocumentFile?):List<DocumentFile>{
    if(root==null||!root.exists())return emptyList()
    val out=ArrayList<DocumentFile>();val stack=ArrayDeque<DocumentFile>();stack.add(root)
    while(stack.isNotEmpty()){
        val item=stack.removeLast()
        if(item.isDirectory)item.listFiles().forEach(stack::addLast)
        else if(item.isFile){val ext=item.name?.substringAfterLast('.',"")?.lowercase(Locale.US).orEmpty();if(item.type?.startsWith("audio/")==true||ext in supportedAudioExtensions)out.add(item)}
    }
    return out.sortedBy{it.name?.lowercase(Locale.getDefault()).orEmpty()}
}
private fun createUniqueOutput(dir:DocumentFile,inputName:String,format:DsreOutputFormat):DocumentFile{
    val base=inputName.substringBeforeLast('.').ifBlank{"audio"}+"_DSRE"
    val ext=when(format){DsreOutputFormat.WAV->"wav";DsreOutputFormat.ALAC->"m4a";DsreOutputFormat.FLAC->"flac";DsreOutputFormat.MP3->"mp3"}
    val mime=when(format){DsreOutputFormat.WAV->"audio/wav";DsreOutputFormat.ALAC->"audio/mp4";DsreOutputFormat.FLAC->"audio/flac";DsreOutputFormat.MP3->"audio/mpeg"}
    var n=1
    while(true){val name=if(n==1)"$base.$ext" else "$base ($n).$ext";if(dir.findFile(name)==null)return dir.createFile(mime,name)?:throw IOException("Unable to create output: $name");n++}
}
private fun displayName(c:Context,u:Uri):String?=runCatching{c.contentResolver.query(u,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else null}}.getOrNull()
@Composable private fun <T> ChoiceField(label:String,selected:T,values:List<T>,title:(T)->String,onSelect:(T)->Unit){var open by rememberSaveable{mutableStateOf(false)};ExposedDropdownMenuBox(open,{open=it}){OutlinedTextField(title(selected),{},readOnly=true,label={Text(label)},trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(open)},modifier=Modifier.menuAnchor().fillMaxWidth());ExposedDropdownMenu(open,{open=false}){values.forEach{v->DropdownMenuItem({Text(title(v))},{onSelect(v);open=false})}}}}
@Composable private fun NormalizedSlider(label:String,value:Float,onValue:(Float)->Unit){val normalized=value.coerceIn(0f,1f);Column{Row(Modifier.fillMaxWidth()){Text(label,Modifier.weight(1f));Text(String.format(Locale.US,"%.2f",normalized),fontWeight=FontWeight.Bold)};Slider(normalized,{onValue(it.coerceIn(0f,1f))},valueRange=0f..1f,steps=99)}}
