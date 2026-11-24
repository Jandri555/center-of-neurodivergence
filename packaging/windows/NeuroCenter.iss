#ifndef AppVersion
  #error Build requires /DAppVersion
#endif
#ifndef AppImage
  #error Build requires /DAppImage
#endif

#define AppName "Center of Neurodivergence"
#define AppGuid "b273a720-1483-4f49-bd16-9f3b1092c3ab"
#define AppExecutable AppName + ".exe"

[Setup]
AppId={{{#AppGuid}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher=NeuroCenter
AppPublisherURL=https://github.com/Jandri555/center-of-neurodivergence
AppSupportURL=https://github.com/Jandri555/center-of-neurodivergence/issues
DefaultDirName={localappdata}\Center of Neurodivergence
DefaultGroupName=Center of Neurodivergence
PrivilegesRequired=lowest
UsePreviousAppDir=yes
DisableDirPage=no
DisableReadyPage=no
DisableWelcomePage=no
DisableProgramGroupPage=yes
DirExistsWarning=no
WizardStyle=modern
InfoBeforeFile=installer-info.es.txt
ShowLanguageDialog=no
Compression=lzma2
SolidCompression=yes
CloseApplications=yes
RestartApplications=no
UninstallDisplayIcon={app}\{#AppExecutable}
UninstallDisplayName=Center of Neurodivergence
ChangesAssociations=yes
OutputBaseFilename=Center-of-Neurodivergence-Setup-{#AppVersion}
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible

[Languages]
Name: "spanish"; MessagesFile: "compiler:Languages\Spanish.isl"

[Tasks]
Name: "desktopicon"; Description: "Crear un acceso directo en el escritorio"

[Files]
Source: "{#AppImage}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{userprograms}\Center of Neurodivergence"; Filename: "{app}\{#AppExecutable}"
Name: "{userdesktop}\Center of Neurodivergence"; Filename: "{app}\{#AppExecutable}"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Classes\.ncapp"; ValueType: string; ValueName: ""; ValueData: "NeuroCenter.ncapp"; Flags: uninsdeletevalue
Root: HKCU; Subkey: "Software\Classes\NeuroCenter.ncapp"; ValueType: string; ValueName: ""; ValueData: "Aplicación de NeuroCenter"; Flags: uninsdeletekey
Root: HKCU; Subkey: "Software\Classes\NeuroCenter.ncapp\DefaultIcon"; ValueType: string; ValueName: ""; ValueData: "{app}\{#AppExecutable},0"
Root: HKCU; Subkey: "Software\Classes\NeuroCenter.ncapp\shell\open\command"; ValueType: string; ValueName: ""; ValueData: """{app}\{#AppExecutable}"" ""%1"""

[Run]
Filename: "{app}\{#AppExecutable}"; Description: "Iniciar Center of Neurodivergence"; Flags: nowait postinstall skipifsilent

[Code]
// Legacy packages used per-user jpackage/WiX, with a Windows Installer
// registry entry. Remove ONLY an exact-match per-user MSI product before
// installing the Inno package to the same directory. Never remove app data.
function FindLegacyMsi(var ProductCode: String): Boolean;
var
  Names: TArrayOfString;
  I: Integer;
  Key: String;
  DisplayName: String;
  IsMsi: Cardinal;
begin
  Result := False;
  ProductCode := '';
  if not RegGetSubkeyNames(HKCU,
      'Software\Microsoft\Windows\CurrentVersion\Uninstall', Names) then
    Exit;
  for I := 0 to GetArrayLength(Names) - 1 do
  begin
    if (Length(Names[I]) <> 38) or (Copy(Names[I], 1, 1) <> '{')
        or (Copy(Names[I], 38, 1) <> '}') then
      Continue;
    Key := 'Software\Microsoft\Windows\CurrentVersion\Uninstall\' + Names[I];
    if RegQueryStringValue(HKCU, Key, 'DisplayName', DisplayName)
        and (CompareText(DisplayName, 'Center of Neurodivergence') = 0)
        and RegQueryDWordValue(HKCU, Key, 'WindowsInstaller', IsMsi)
        and (IsMsi = 1) then
    begin
      ProductCode := Names[I];
      Result := True;
      Exit;
    end;
  end;
end;

procedure CurPageChanged(CurPageID: Integer);
var
  ProductCode: String;
begin
  if CurPageID = wpReady then
  begin
    if FileExists(ExpandConstant('{app}\{#AppExecutable}'))
        or FindLegacyMsi(ProductCode) then
      WizardForm.NextButton.Caption := 'Actualizar'
    else
      WizardForm.NextButton.Caption := 'Instalar';
  end;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  ProductCode: String;
  ExitCode: Integer;
begin
  Result := '';
  // A newer Inno installation is handled by AppId/UsePreviousAppDir.
  // Only an older, exactly identified per-user MSI needs explicit removal.
  if not FindLegacyMsi(ProductCode) then
    Exit;
  Log('Migrating legacy jpackage MSI: ' + ProductCode);
  if not Exec(ExpandConstant('{sys}\msiexec.exe'),
      '/x ' + ProductCode + ' /qn /norestart', '',
      SW_HIDE, ewWaitUntilTerminated, ExitCode) then
  begin
    Result := 'No se pudo iniciar la desinstalación de la versión anterior. ' +
      'Cierra NeuroCenter e inténtalo de nuevo.';
    Exit;
  end;
  if ExitCode = 3010 then
  begin
    NeedsRestart := True;
    Result := 'Windows necesita reiniciarse para retirar el instalador ' +
      'anterior. Reinicia antes de actualizar; tus datos no se borrarán.';
    Exit;
  end;
  if (ExitCode <> 0) and (ExitCode <> 1605) then
  begin
    Result := 'Windows no pudo retirar la versión anterior (código ' +
      IntToStr(ExitCode) + '). Cierra NeuroCenter e inténtalo de nuevo.';
  end;
end;
