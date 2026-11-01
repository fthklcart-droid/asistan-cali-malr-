; FK JARVIS — Inno Setup 6 kurulum betiği
; packaging/build_windows.py --installer tarafından derlenir; elle: ISCC /DAppVersion=0.3.0 /DSourceDir=... /DOutputDir=... fk_jarvis.iss
#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif
#ifndef SourceDir
  #define SourceDir "..\dist\FK JARVIS"
#endif
#ifndef OutputDir
  #define OutputDir "..\dist\installer"
#endif

[Setup]
AppId={{8F3C2B61-6E0A-4E7B-9C1D-FA11E7A2C0DE}
AppName=FK JARVIS
AppVersion={#AppVersion}
AppVerName=FK JARVIS {#AppVersion}
AppPublisher=FK Art Medya
DefaultDirName={localappdata}\Programs\FK JARVIS
DefaultGroupName=FK JARVIS
DisableProgramGroupPage=yes
; Yönetici izni gerekmez: kullanıcı klasörüne kurulur, uygulama verisi (data\) yazılabilir kalır
PrivilegesRequired=lowest
OutputDir={#OutputDir}
OutputBaseFilename=FK_JARVIS_Kurulum_{#AppVersion}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
UninstallDisplayIcon={app}\FK JARVIS.exe
UninstallDisplayName=FK JARVIS
CloseApplications=yes

[Languages]
Name: "turkish"; MessagesFile: "compiler:Languages\Turkish.isl"

[Tasks]
Name: "desktopicon"; Description: "Masaüstü kısayolu oluştur"; GroupDescription: "Ek simgeler:"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
; İlk kurulumda .env şablondan oluşturulur; güncellemede kullanıcının anahtarlarına dokunulmaz
Source: "{#SourceDir}\.env.example"; DestDir: "{app}"; DestName: ".env"; Flags: onlyifdoesntexist uninsneveruninstall

[Icons]
Name: "{group}\FK JARVIS"; Filename: "{app}\FK JARVIS.exe"
Name: "{group}\FK JARVIS'i kaldır"; Filename: "{uninstallexe}"
Name: "{userdesktop}\FK JARVIS"; Filename: "{app}\FK JARVIS.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\FK JARVIS.exe"; Description: "FK JARVIS'i başlat"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
; Program dosyaları kaldırılır. Kullanıcı verisi (data\, .env) bilerek SİLİNMEZ; elle silinebilir.
Type: files; Name: "{app}\jarvis.log"
