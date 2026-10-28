#!/bin/sh
# Génère index.html (app autonome installable) à partir de app.html
cd "$(dirname "$0")"
{ printf '<!doctype html>\n<html lang="fr"><head><meta charset="utf-8">\n<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">\n<meta name="theme-color" content="#1f56c9"><link rel="manifest" href="manifest.webmanifest"><link rel="icon" href="icon.svg"><link rel="apple-touch-icon" href="icon.svg">\n<style>:root{padding-top:env(safe-area-inset-top,0px)}body{margin:0;font:14px system-ui,sans-serif}img{max-width:100%%}[hidden]{display:none!important}*{box-sizing:border-box}</style>\n</head><body>\n'
  cat app.html
  printf '\n<script>if("serviceWorker" in navigator)navigator.serviceWorker.register("sw.js").catch(()=>{})</script>\n</body></html>\n'; } > index.html
