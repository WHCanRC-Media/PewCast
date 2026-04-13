!include "MUI2.nsh"

Name "PewCast"
OutFile "pewcast-setup.exe"
InstallDir "$PROGRAMFILES\PewCast"
RequestExecutionLevel admin

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "English"

Function .onInit
    ; Detect legacy WHCanRC Assisted Listening install and offer to remove it
    ReadRegStr $0 HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\WHCanRCAssistedListening" "UninstallString"
    StrCmp $0 "" done_legacy 0

    MessageBox MB_YESNO|MB_ICONQUESTION \
        "A previous installation of WHCanRC Assisted Listening was detected.$\n$\nPewCast is the renamed version of the same program. Leaving the old install in place will cause both to run at startup and conflict on port 8080.$\n$\nUninstall the old version now?" \
        /SD IDYES IDNO done_legacy

    ; Run the legacy uninstaller and wait for it to finish
    ExecWait '$0 /S _?=$INSTDIR' $1
    ; The legacy uninstaller leaves its own exe behind when invoked with _?=
    ReadRegStr $2 HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\WHCanRCAssistedListening" "UninstallString"
    StrCmp $2 "" +2 0
    Delete $2

    ; Best-effort cleanup in case the legacy uninstaller didn't catch everything
    Delete "$SMSTARTUP\WHCanRC Assisted Listening.lnk"
    Delete "$SMPROGRAMS\WHCanRC Assisted Listening\WHCanRC Assisted Listening.lnk"
    RMDir "$SMPROGRAMS\WHCanRC Assisted Listening"
    nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="WHCanRC Assisted Listening"'
    nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="WHCanRC Assisted Listening UDP"'
    DeleteRegKey HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\WHCanRCAssistedListening"

done_legacy:

    ; Detect existing PewCast install and handle upgrade
    ReadRegStr $0 HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\PewCast" "UninstallString"
    StrCmp $0 "" done_upgrade 0

    MessageBox MB_YESNO|MB_ICONQUESTION \
        "An existing PewCast installation was detected.$\n$\nContinue to upgrade it in place? Your config.toml will be preserved." \
        /SD IDYES IDYES do_upgrade
    Abort

do_upgrade:
    ; Stop any running pewcast.exe so the binary can be overwritten
    nsExec::ExecToLog 'taskkill /F /IM pewcast.exe /T'

done_upgrade:
FunctionEnd

Section "Install"
    SetShellVarContext current
    SetOutPath $INSTDIR

    ; Copy the binary
    File "..\pewcast-windows-x86_64.exe"
    Rename "$INSTDIR\pewcast-windows-x86_64.exe" "$INSTDIR\pewcast.exe"

    ; Write default config if it doesn't exist
    IfFileExists "$INSTDIR\config.toml" +2
    File "/oname=config.toml" "..\config.toml.example"

    ; Add firewall rules (delete first so repeated installs don't stack duplicates)
    nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="PewCast"'
    nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="PewCast UDP"'
    nsExec::ExecToLog 'netsh advfirewall firewall add rule name="PewCast" dir=in action=allow protocol=TCP localport=8080'
    nsExec::ExecToLog 'netsh advfirewall firewall add rule name="PewCast UDP" dir=in action=allow protocol=UDP localport=8080'

    ; Create Start Menu shortcut
    CreateDirectory "$SMPROGRAMS\PewCast"
    CreateShortCut "$SMPROGRAMS\PewCast\PewCast.lnk" "$INSTDIR\pewcast.exe"

    ; Run on startup
    CreateShortCut "$SMSTARTUP\PewCast.lnk" "$INSTDIR\pewcast.exe"

    ; Create uninstaller
    WriteUninstaller "$INSTDIR\uninstall.exe"

    ; Add to Add/Remove Programs
    WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\PewCast" "DisplayName" "PewCast"
    WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\PewCast" "UninstallString" "$\"$INSTDIR\uninstall.exe$\""
    WriteRegStr HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\PewCast" "Publisher" "PewCast"
SectionEnd

Section "Uninstall"
    SetShellVarContext current
    ; Remove shortcuts
    Delete "$SMSTARTUP\PewCast.lnk"
    Delete "$SMPROGRAMS\PewCast\PewCast.lnk"
    RMDir "$SMPROGRAMS\PewCast"

    ; Remove firewall rules
    nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="PewCast"'
    nsExec::ExecToLog 'netsh advfirewall firewall delete rule name="PewCast UDP"'

    ; Remove files
    Delete "$INSTDIR\pewcast.exe"
    Delete "$INSTDIR\uninstall.exe"
    ; Leave config.toml in case user wants to preserve settings
    RMDir "$INSTDIR"

    ; Remove registry entries
    DeleteRegKey HKLM "Software\Microsoft\Windows\CurrentVersion\Uninstall\PewCast"
SectionEnd
