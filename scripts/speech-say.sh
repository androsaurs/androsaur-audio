#!/usr/bin/env bash
# Фраза через реальный путь речи (тот, что у ответов) с паузами простоя; печатает первый звук / синтез / длину звука.
#   scripts/speech-say.sh [-s SERIAL] [-t THREADS] [-n ПОВТОРОВ] [-g ПАУЗА_МС] [-x "текст"] [-e движок]
# Пример: scripts/speech-say.sh -n 3 -g 0 ; scripts/speech-say.sh -n 3 -g 20000   (сравнить подряд и после простоя)
set -euo pipefail
SERIAL=""; ENGINE=""; T=""; N=3; G=0; TEXT="Проверка скорости речи, тестовая фраза."
while getopts "s:t:n:g:x:e:" o; do case $o in s) SERIAL=$OPTARG;; t) T=$OPTARG;; n) N=$OPTARG;; g) G=$OPTARG;; x) TEXT=$OPTARG;; e) ENGINE=$OPTARG;; *) exit 2;; esac; done
ADB=(adb); [ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")
tag="say$$"; args="--es tag $tag --ei repeat $N --el gap_ms $G --es text '$TEXT'"   # одним куском: adb shell иначе делит текст по пробелам
[ -n "$T" ] && args+=" --ei threads $T"
[ -n "$ENGINE" ] && args+=" --es engine $ENGINE"   # сохраняется как настройка телефона
"${ADB[@]}" logcat -c
"${ADB[@]}" shell "am broadcast -a dev.hram.androsaur.audio.SPEECH_SAY -p dev.hram.androsaur.audio $args" >/dev/null
limit=$(( (N * (G / 1000 + 20)) / 3 + 30 ))
for _ in $(seq "$limit"); do "${ADB[@]}" logcat -d -s SpeechBench | grep -q "$tag \(DONE\|ERROR\)" && break; sleep 3; done
"${ADB[@]}" logcat -d -s SpeechBench | sed -n "s/.*SpeechBench: //p" | grep "^$tag "
