#!/usr/bin/env bash
# Non-invasive health checks for a device running Echo Home, over adb.
# Usage:
#   scripts/echo-check.sh info            one-off snapshot (device, RAM, CPU, temperature, Echo Home memory)
#   scripts/echo-check.sh sample [MIN]    one CSV line per minute for MIN minutes (default 60) into echo-samples.csv
# Never records audio. Reads only system counters.
set -euo pipefail

PKG=com.echohome.app
adb get-state >/dev/null 2>&1 || { echo "No device: check 'adb devices'." >&2; exit 1; }
sh() { adb shell "$@" | tr -d '\r'; }

# Highest thermal zone in °C (0 if the device exposes none).
temp_c() {
  local best=0 v
  for v in $(sh 'cat /sys/class/thermal/thermal_zone*/temp 2>/dev/null'); do
    [[ "$v" =~ ^-?[0-9]+$ ]] || continue
    (( v > 1000 )) && v=$(( v / 1000 ))
    (( v > best )) && best=$v
  done
  echo "$best"
}

# Echo Home total PSS in MB (0 if the app is not running).
app_mb() {
  local kb
  kb=$(sh "dumpsys meminfo $PKG" | awk '/TOTAL PSS:|^ *TOTAL / {for(i=1;i<=NF;i++) if ($i ~ /^[0-9]+$/) {print $i; exit}}')
  echo $(( ${kb:-0} / 1024 ))
}

# CPU use of Echo Home in % of one core (0 if not running); one `top` iteration.
app_cpu() {
  sh "top -b -n 1 -q 2>/dev/null | grep $PKG | head -1" | awk '{for(i=1;i<=NF;i++) if ($i ~ /^[0-9.]+$/ && i>=8) {print $i; exit}}' | grep . || echo 0
}

case "${1:-info}" in
  info)
    echo "== Device =="
    echo "model:   $(sh getprop ro.product.model)  ($(sh getprop ro.product.device))"
    echo "android: $(sh getprop ro.build.version.release)  build: $(sh getprop ro.build.display.id)"
    echo "== Memory =="
    sh 'grep -E "MemTotal|MemAvailable" /proc/meminfo'
    echo "== CPU =="
    echo "cores:   $(sh 'grep -c ^processor /proc/cpuinfo')"
    sh 'cat /proc/loadavg'
    echo "== Temperature (highest zone) =="
    echo "$(temp_c) °C"
    echo "== Storage =="
    sh 'df -h /data | tail -1'
    echo "== Echo Home =="
    echo "memory:  $(app_mb) MB   cpu: $(app_cpu) %"
    echo "installed: $(sh "pm list packages $PKG" | head -1)"
    echo "meld:      $(sh 'pm list packages com.meld' | head -2 | tr '\n' ' ')"
    ;;
  sample)
    minutes="${2:-60}"
    out=echo-samples.csv
    [[ -f $out ]] || echo "time,temp_c,mem_available_mb,echohome_mb,echohome_cpu_pct" > "$out"
    for (( i = 0; i < minutes; i++ )); do
      avail=$(( $(sh "awk '/MemAvailable/ {print \$2}' /proc/meminfo") / 1024 ))
      echo "$(date +%H:%M:%S),$(temp_c),$avail,$(app_mb),$(app_cpu)" | tee -a "$out"
      sleep 60
    done
    ;;
  *) echo "usage: $0 info | sample [minutes]" >&2; exit 2 ;;
esac
