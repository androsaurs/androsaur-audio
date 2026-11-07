#!/usr/bin/env bash
# Замер скорости синтеза речи на телефоне без участия человека (нужен adb и сборка с BenchTrigger).
#   scripts/speech-bench.sh [-s SERIAL] [-p] [-c] [tag:threads[:engine] ...]
#   -p  говорить вслух (добавляет «до первого звука»)   -c  на время замера остановить камеру (androsaur-camera)
# Без вариантов: один замер с текущими настройками. Пример: scripts/speech-bench.sh t1:1 t2:2 t4:4
# Печатает таблицу по фразам и нагрузку процессора перед замером. Внимание: threads сохраняется как настройка телефона.
set -euo pipefail
SERIAL=""; PLAY=false; NOCAM=false
while getopts "s:pc" o; do case $o in s) SERIAL=$OPTARG;; p) PLAY=true;; c) NOCAM=true;; *) exit 2;; esac; done
shift $((OPTIND-1))
ADB=(adb); [ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")
CAM=dev.hram.androsaur.camera
[ $# -gt 0 ] || set -- "run"
$NOCAM && "${ADB[@]}" shell am force-stop $CAM
trap '$NOCAM && "${ADB[@]}" shell monkey -p $CAM -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true' EXIT
echo "# нагрузка перед замером:"; "${ADB[@]}" shell top -b -n 1 -m 5 | sed -n 4,10p
for v in "$@"; do
  IFS=: read -r tag threads engine <<<"$v"
  args=(--es tag "$tag" --ez play $PLAY)
  [ -n "${threads:-}" ] && args+=(--ei threads "$threads")
  [ -n "${engine:-}" ] && args+=(--es engine "$engine")
  "${ADB[@]}" logcat -c
  "${ADB[@]}" shell am broadcast -a dev.hram.androsaur.audio.SPEECH_BENCH -p dev.hram.androsaur.audio "${args[@]}" >/dev/null
  for _ in $(seq 120); do "${ADB[@]}" logcat -d -s SpeechBench | grep -q "$tag \(DONE\|ERROR\)" && break; sleep 3; done
  echo "== $v"; "${ADB[@]}" logcat -d -s SpeechBench | sed -n "s/.*SpeechBench: //p" | grep "^$tag "
done
