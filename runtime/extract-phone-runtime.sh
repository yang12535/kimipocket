#!/data/data/com.termux/files/usr/bin/bash
# 在手机上运行：把跑通 kimi-code 所需的最小 Termux 环境打成 tar.gz 输出到 stdout
set -e
export PATH=/data/data/com.termux/files/usr/bin:$PATH

WANT="bash coreutils findutils grep sed gawk tar gzip xz-utils less debianutils \
ncurses ncurses-utils termux-exec termux-tools ca-certificates openssl zlib \
nodejs npm git curl resolv-conf libresolv-wrapper dash readline libandroid-support"

CLOSE=""
QUEUE="$WANT"
while [ -n "$QUEUE" ]; do
  set -- $QUEUE; p=$1; shift; QUEUE="$*"
  case " $CLOSE " in *" $p "*) continue;; esac
  dpkg -s "$p" >/dev/null 2>&1 || { echo "SKIP(not installed): $p" >&2; continue; }
  CLOSE="$CLOSE $p"
  for d in $(apt-cache depends "$p" 2>/dev/null | grep -E '^[[:space:]]*\|?[[:space:]]*(Pre)?Depends:' | awk '{print $NF}'); do
    case "$d" in \<*\>) continue;; esac
    case " $CLOSE $QUEUE " in *" $d "*) ;; *) QUEUE="$QUEUE $d";; esac
  done
done
NPKGS=$(echo $CLOSE | wc -w)
echo "PKGS($NPKGS): $CLOSE" >&2

LIST=/data/data/com.termux/files/usr/tmp/rtfiles.txt
: > "$LIST"
for p in $CLOSE; do dpkg -L "$p"; done | sort -u | while read -r f; do
  if [ -f "$f" ] || [ -L "$f" ]; then printf '%s\n' "$f" >> "$LIST"; fi
done

# kimi-code 全局包本体 + bin 软链
find /data/data/com.termux/files/usr/lib/node_modules/@moonshot-ai \( -type f -o -type l \) >> "$LIST"
echo "/data/data/com.termux/files/usr/bin/kimi" >> "$LIST"

# 过滤噪声
grep -v -e '^/data/data/com.termux/files/usr/var/lib/dpkg' \
        -e '/share/man/' -e '/share/doc/' -e '/share/info/' "$LIST" > "$LIST.f" || true
mv "$LIST.f" "$LIST"

TOTAL=$(cat "$LIST" | wc -l)
echo "FILES: $TOTAL" >&2
du -sch $(cat "$LIST" | head -100000) 2>/dev/null | tail -1 >&2 || true

tar czf - -C / -T "$LIST" 2>/dev/null
rm -f "$LIST"
echo "DONE" >&2
