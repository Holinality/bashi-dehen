package com.holi.prdbus;
public final class SettingsRulesSelfTest {
 public static void main(String[] args){
  require(!PublicBuildConfig.OFFICIAL_UPDATES,"self-signed public build must not offer maintainer-signed APK updates");
  require(!PublicBuildConfig.ALLOW_UNDOCUMENTED_METRO_FETCH,"public source silently enabled undocumented metro downloads");
  String hash=new String(new char[64]).replace('\0','a');
  require(UpdatePolicy.validDownload("https://bus.holinality.com/prdbus/app-2.1.0.apk",hash),"valid update refused");
  for(String bad:new String[]{"http://bus.holinality.com/prdbus/app.apk","https://evil.test/prdbus/app.apk","https://holinality.com/prdbus/app.apk","https://bus.holinality.com/prdbus/../app.apk","https://bus.holinality.com/prdbus/%2e%2e/app.apk","https://bus.holinality.com@evil.test/prdbus/app.apk"})
   require(!UpdatePolicy.validDownload(bad,hash),"unsafe update accepted");
  require(!UpdatePolicy.validDownload("https://bus.holinality.com/prdbus/app.apk","bad"),"invalid hash accepted");
  AppSettings.customKey="123456789012345678901234567890abcf";
  require(!AppSettings.saveKey("") && !AppSettings.saveKey(null),"empty input restored the built-in key");
  require(!AppSettings.customKey.isEmpty(),"rejected key erased the saved credential");
  require(AppSettings.credentialUrl("https://restapi.amap.com/v3/test?key=old&x=1").endsWith("key="+AppSettings.customKey+"&x=1"),"custom key substitution failed");
  require(AppSettings.credentialUrl("https://other.test/?key=old").endsWith("key=old"),"key leaked to another service");
  AppSettings.customKey="";
  for(boolean night:new boolean[]{false,true,false}){
   Ui.setNight(night);
   for(int bg:new int[]{Ui.BG,Ui.SURFACE,Ui.RAISED,Ui.LIGHT_BLUE,Ui.LIGHT_ORANGE})
    for(int ink:new int[]{Ui.TEXT,Ui.MUTED,Ui.PRIMARY,Ui.ACCENT})require(ReadableColors.contrast(ink,bg)>=4.5,"unreadable switched theme");
   require(ReadableColors.contrast(Ui.ON_PRIMARY,Ui.PRIMARY)>=4.5,"primary button unreadable");
  }
  System.out.println("SettingsRulesSelfTest passed (theme roundtrip, key routing, update URLs)");
 }
 static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
}
