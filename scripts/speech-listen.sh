#!/usr/bin/env bash
# Слышит ли телефон собственный голос после реплики: сказать фразу, затем 3 с слушать, как команда, ждущая ответ.
#   scripts/speech-listen.sh [-s SERIAL] [-n ПОВТОРОВ] [-e движок] [-x "текст"] [-d "фраза вместо человека"]   (в комнате молчать)
# Печатает по строке RecGate на повтор: floorDbfs (шум, по нему считается порог), thresholdDbfs, peakDbfs.
# Обычная речь у телефона около -50 dBFS; порог выше этого значит, что запись речь не услышит.
set -euo pipefail
SERIAL=""; DURING=""; N=3; ENGINE=""; TEXT="Что вспомнить?"
while getopts "s:n:e:x:d:" o; do case $o in s) SERIAL=$OPTARG;; n) N=$OPTARG;; e) ENGINE=$OPTARG;; x) TEXT=$OPTARG;; d) DURING=$OPTARG;; *) exit 2;; esac; done
ADB=(adb); [ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")
tag="listen$$"; args="--es tag $tag --ei repeat $N --es text '$TEXT'"   # -x " " — контроль без фразы
[ -n "$ENGINE" ] && args+=" --es engine $ENGINE"
[ -n "$DURING" ] && args+=" --es during '$DURING'"   # фраза, которую телефон скажет во время слушания вместо человека
"${ADB[@]}" logcat -c
"${ADB[@]}" shell "am broadcast -a dev.hram.androsaur.audio.SPEECH_LISTEN -p dev.hram.androsaur.audio $args" >/dev/null
for _ in $(seq $((N * 6 + 20))); do "${ADB[@]}" logcat -d -s SpeechBench | grep -q "$tag \(DONE\|ERROR\)" && break; sleep 2; done
"${ADB[@]}" logcat -d -s SpeechBench:I RecGate:I | sed -n "s/.*\(SpeechBench\|RecGate\): //p" | grep -v "^$" | grep -E "^$tag|floorDbfs"
