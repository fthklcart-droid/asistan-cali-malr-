package com.fkartmedia.jarvis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Navy=Color(0xFF060B14); private val Panel=Color(0xFF0D192A); private val Cyan=Color(0xFF38BDF8); private val Purple=Color(0xFF8B5CF6); private val Muted=Color(0xFF91A8C4)
class MainActivity:ComponentActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{JarvisApp()}}}
@Composable fun JarvisApp(){
 var prompt by remember{mutableStateOf("")};var provider by remember{mutableStateOf("Claude")};var status by remember{mutableStateOf("Windows ajanı bağlantısı bekleniyor")};var tab by remember{mutableStateOf("Genel")};var messages by remember{mutableStateOf(listOf("FK JARVIS mobil merkezi hazır.","Sunucu bağlantısı yapılandırıldığında görevlerini buradan yönetebilirsin."))}
 MaterialTheme(colorScheme=darkColorScheme(primary=Cyan,background=Navy,surface=Panel)){
  Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Navy,Color(0xFF10172B),Navy))).padding(16.dp)){
   Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("FK JARVIS",color=Color.White,fontSize=25.sp,fontWeight=FontWeight.ExtraBold);Text("MOBILE COMMAND CENTER  •  FK ART MEDYA",color=Cyan,fontSize=9.sp,fontWeight=FontWeight.Bold)};Surface(color=Color(0xFF12352F),shape=RoundedCornerShape(30.dp)){Text("● SECURE",Modifier.padding(horizontal=10.dp,vertical=6.dp),color=Color(0xFF6EE7B7),fontSize=9.sp)}}
   Spacer(Modifier.height(16.dp))
   Card(colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(22.dp),modifier=Modifier.fillMaxWidth().border(1.dp,Color(0xFF1C5277),RoundedCornerShape(22.dp))){Column(Modifier.padding(16.dp)){
    Text("HOLOGRAPHIC CORE",color=Cyan,fontSize=10.sp,fontWeight=FontWeight.Bold);Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(112.dp).background(Brush.radialGradient(listOf(Color(0xFF164A70),Color(0xFF111A30),Navy)),CircleShape).border(1.dp,Cyan,CircleShape),contentAlignment=Alignment.Center){Text("◉",color=Cyan,fontSize=68.sp)};Spacer(Modifier.width(14.dp));Column{Text("Sistem hazır.",color=Color.White,fontSize=20.sp,fontWeight=FontWeight.Bold);Text("Claude • Gemini • Windows Agent",color=Muted,fontSize=11.sp);Spacer(Modifier.height(8.dp));Text(status,color=Cyan,fontSize=11.sp)}}
   }}
   Spacer(Modifier.height(12.dp));Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("Claude","Gemini Ses").forEach{FilterChip(selected=provider==it,onClick={provider=it},label={Text(it)},colors=FilterChipDefaults.filterChipColors(selectedContainerColor=Color(0xFF123B5A),selectedLabelColor=Cyan))}}
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth()) {listOf("Genel","Görevler","Onaylar").forEach{item->FilterChip(selected=tab==item,onClick={tab=item},label={Text(item)},modifier=Modifier.weight(1f))}}
   Spacer(Modifier.height(8.dp));Text(if(tab=="Genel")"KOMUTA PANELİ" else tab.uppercase(),color=Cyan,fontSize=10.sp,fontWeight=FontWeight.Bold)
   if(tab=="Genel"){
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){MiniTile("WINDOWS","Yerel ajan",Modifier.weight(1f));MiniTile("CREATIVE","Adobe + CapCut",Modifier.weight(1f))};Spacer(Modifier.height(8.dp));Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){MiniTile("CLOUD AI","Claude Agent",Modifier.weight(1f));MiniTile("VOICE","Gemini Live",Modifier.weight(1f))}
   }
   LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top=10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){items(messages){msg->Card(colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(12.dp)){Text(msg,Modifier.padding(12.dp),color=Color.White,fontSize=12.sp)}}}
   OutlinedTextField(value=prompt,onValueChange={prompt=it},modifier=Modifier.fillMaxWidth(),label={Text("JARVIS'e görev ver")},minLines=2,shape=RoundedCornerShape(14.dp),colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=Cyan,unfocusedBorderColor=Color(0xFF29415D),focusedTextColor=Color.White,unfocusedTextColor=Color.White))
   Spacer(Modifier.height(8.dp));Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={if(prompt.isNotBlank()){messages=messages+"[$provider] Taslak görev: $prompt"+"\nSunucu bağlantısı yapılandırılmadı; henüz gönderilmedi.";prompt=""}},modifier=Modifier.weight(1f),shape=RoundedCornerShape(12.dp)){Text("✦ Görev hazırla")};OutlinedButton(onClick={status="Mikrofon/voice backend bağlantısı yapılandırılmalı"},shape=RoundedCornerShape(12.dp)){Text("🎙 Ses")}}
   Spacer(Modifier.height(8.dp));Text("Mobil arayüz prototipi • API bağlantısı yapılandırılınca görev gönderimi ve sesli oturum etkinleşir.",color=Muted,fontSize=9.sp)
  }
 }
}
@Composable private fun MiniTile(title:String,desc:String,modifier:Modifier=Modifier){Card(modifier=modifier,colors=CardDefaults.cardColors(containerColor=Panel),shape=RoundedCornerShape(14.dp)){Column(Modifier.padding(12.dp)){Text(title,color=Cyan,fontSize=9.sp,fontWeight=FontWeight.Bold);Text(desc,color=Color.White,fontSize=12.sp,fontWeight=FontWeight.SemiBold);Text("● BAĞLANTI BEKLENİYOR",color=Muted,fontSize=8.sp)}}}
