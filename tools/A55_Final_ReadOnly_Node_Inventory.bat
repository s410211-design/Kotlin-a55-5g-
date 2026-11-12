@echo off
setlocal EnableExtensions DisableDelayedExpansion
title A55 Final Read-only Node Inventory

set "ADB=adb"
if exist "%~dp0adb.exe" set "ADB=%~dp0adb.exe"

for /f "delims=" %%T in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmmss" 2^>nul') do set "STAMP=%%T"
if not defined STAMP set "STAMP=unknown"
set "REPORT=%USERPROFILE%\Desktop\A55_Final_Node_Inventory_%STAMP%.txt"

"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
  echo ADB device unavailable. Check USB debugging and authorization.
  pause
  exit /b 1
)

> "%REPORT%" echo ============================================================
>>"%REPORT%" echo A55 FINAL READ-ONLY NODE INVENTORY
>>"%REPORT%" echo Generated: %DATE% %TIME%
>>"%REPORT%" echo Device target: Samsung SM-A5560 / Xclipse 530
>>"%REPORT%" echo This script does not write to any device node or setting.
>>"%REPORT%" echo ============================================================

>>"%REPORT%" echo.
>>"%REPORT%" echo [01 DEVICE AND SECURITY]
"%ADB%" devices -l >>"%REPORT%" 2>&1
"%ADB%" shell id >>"%REPORT%" 2>&1
"%ADB%" shell getenforce >>"%REPORT%" 2>&1
"%ADB%" shell getprop ro.product.model >>"%REPORT%" 2>&1
"%ADB%" shell getprop ro.soc.model >>"%REPORT%" 2>&1
"%ADB%" shell getprop ro.hardware >>"%REPORT%" 2>&1
"%ADB%" shell getprop ro.build.version.release >>"%REPORT%" 2>&1
"%ADB%" shell getprop ro.build.version.sdk >>"%REPORT%" 2>&1
"%ADB%" shell getprop ro.build.display.id >>"%REPORT%" 2>&1
"%ADB%" shell getprop ro.build.fingerprint >>"%REPORT%" 2>&1
"%ADB%" shell getprop ro.build.version.security_patch >>"%REPORT%" 2>&1
"%ADB%" shell uname -a >>"%REPORT%" 2>&1

>>"%REPORT%" echo.
>>"%REPORT%" echo [02 GPU DIRECTORY METADATA - INCLUDES CONTROL-NODE NAMES ONLY]
"%ADB%" shell ls -laZ /sys/kernel/gpu/ >>"%REPORT%" 2>&1

>>"%REPORT%" echo.
>>"%REPORT%" echo [03 GPU READ-ONLY TELEMETRY CONTENTS]
"%ADB%" shell "for f in ctx_mem_info gpu_available_governor gpu_busy gpu_clock gpu_freq_table gpu_governor gpu_max_clock gpu_min_clock gpu_mm_min_clock gpu_model gpu_siop_max_clock gpu_tmu mem_info sgpu_pm_monitor; do echo NODE:$f; cat /sys/kernel/gpu/$f 2>&1; done" >>"%REPORT%" 2>&1

>>"%REPORT%" echo.
>>"%REPORT%" echo [04 ALL DEVFREQ DEVICES AND READ-ONLY ATTRIBUTES]
"%ADB%" shell "for d in /sys/class/devfreq/*; do [ -d $d ] || continue; echo DEVFREQ:$d; ls -ldZ $d 2>&1; for f in name governor cur_freq available_frequencies min_freq max_freq target_freq polling_interval trans_stat status load; do echo ATTR:$f; cat $d/$f 2>&1; done; done" >>"%REPORT%" 2>&1

>>"%REPORT%" echo.
>>"%REPORT%" echo [05 ALL CPUFREQ POLICIES AND READ-ONLY ATTRIBUTES]
"%ADB%" shell "for d in /sys/devices/system/cpu/cpufreq/policy*; do [ -d $d ] || continue; echo POLICY:$d; ls -ldZ $d 2>&1; for f in affected_cpus related_cpus scaling_driver scaling_governor scaling_available_governors scaling_available_frequencies scaling_cur_freq scaling_min_freq scaling_max_freq cpuinfo_min_freq cpuinfo_max_freq cpuinfo_cur_freq cpuinfo_transition_latency bios_limit stats/time_in_state; do echo ATTR:$f; cat $d/$f 2>&1; done; done" >>"%REPORT%" 2>&1
"%ADB%" shell cat /sys/devices/system/cpu/present >>"%REPORT%" 2>&1
"%ADB%" shell cat /sys/devices/system/cpu/online >>"%REPORT%" 2>&1

>>"%REPORT%" echo.
>>"%REPORT%" echo [06 THERMAL ZONES AND COOLING DEVICES]
"%ADB%" shell ls -laZ /sys/class/thermal/ >>"%REPORT%" 2>&1
"%ADB%" shell "for d in /sys/class/thermal/thermal_zone*; do [ -e $d ] || continue; echo THERMAL_ZONE:$d; ls -ldZ $d 2>&1; for f in type temp mode policy sustainable_power; do echo ATTR:$f; cat $d/$f 2>&1; done; done" >>"%REPORT%" 2>&1
"%ADB%" shell "for d in /sys/class/thermal/cooling_device*; do [ -e $d ] || continue; echo COOLING_DEVICE:$d; ls -ldZ $d 2>&1; for f in type cur_state max_state; do echo ATTR:$f; cat $d/$f 2>&1; done; done" >>"%REPORT%" 2>&1
"%ADB%" shell dumpsys thermalservice >>"%REPORT%" 2>&1

>>"%REPORT%" echo.
>>"%REPORT%" echo [07 POWER SUPPLY READ-ONLY ATTRIBUTES]
"%ADB%" shell ls -laZ /sys/class/power_supply/ >>"%REPORT%" 2>&1
"%ADB%" shell "for d in /sys/class/power_supply/*; do [ -d $d ] || continue; echo POWER_SUPPLY:$d; ls -ldZ $d 2>&1; for f in type status present capacity voltage_now current_now temp technology charge_counter charge_full charge_full_design cycle_count; do echo ATTR:$f; cat $d/$f 2>&1; done; done" >>"%REPORT%" 2>&1
"%ADB%" shell dumpsys battery >>"%REPORT%" 2>&1

>>"%REPORT%" echo.
>>"%REPORT%" echo [08 MEMORY AND GPU MEMORY]
"%ADB%" shell cat /proc/meminfo >>"%REPORT%" 2>&1
"%ADB%" shell cat /proc/vmstat >>"%REPORT%" 2>&1
"%ADB%" shell cat /proc/swaps >>"%REPORT%" 2>&1
"%ADB%" shell cat /sys/kernel/gpu/mem_info >>"%REPORT%" 2>&1

>>"%REPORT%" echo.
>>"%REPORT%" echo [09 THERMAL SERVICE BEFORE 60-SAMPLE WINDOW]
"%ADB%" shell dumpsys thermalservice >>"%REPORT%" 2>&1

>>"%REPORT%" echo.
>>"%REPORT%" echo [10 ONE-SESSION 60-SAMPLE GPU AND CPU TRACE]
"%ADB%" shell "i=1; while [ $i -le 60 ]; do echo; echo SAMPLE:$i; date; echo GPU_BUSY; cat /sys/kernel/gpu/gpu_busy 2>&1; echo GPU_CLOCK; cat /sys/kernel/gpu/gpu_clock 2>&1; echo SGPU_CUR_FREQ; cat /sys/class/devfreq/10e00000.sgpu/cur_freq 2>&1; echo SGPU_TARGET_FREQ; cat /sys/class/devfreq/10e00000.sgpu/target_freq 2>&1; echo GPU_TMU; cat /sys/kernel/gpu/gpu_tmu 2>&1; echo CPU_POLICY0; cat /sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq 2>&1; echo CPU_POLICY4; cat /sys/devices/system/cpu/cpufreq/policy4/scaling_cur_freq 2>&1; i=$((i+1)); sleep 1; done" >>"%REPORT%" 2>&1

>>"%REPORT%" echo.
>>"%REPORT%" echo [11 THERMAL SERVICE AFTER 60-SAMPLE WINDOW]
"%ADB%" shell dumpsys thermalservice >>"%REPORT%" 2>&1

>>"%REPORT%" echo.
>>"%REPORT%" echo [12 END]
>>"%REPORT%" echo Finished: %DATE% %TIME%

echo.
echo Final inventory completed.
echo Report saved to:
echo "%REPORT%"
echo.
echo This is intended to be the LAST PC-ADB inventory run.
echo Review the report before sharing it.
pause
