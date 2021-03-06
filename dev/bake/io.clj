(ns bake.io
  (:use cake
        [clojure.java.io :only [copy]])
  (:import (java.io File FileInputStream FileOutputStream PrintStream PrintWriter InputStreamReader OutputStreamWriter)
           (java.net JarURLConnection)
           (clojure.lang Atom LineNumberingPushbackReader)))

(defn resource-stream [name]
  (if-let [url (.findResource (.getClassLoader clojure.lang.RT) name)]
    (let [conn (.openConnection url)]
      (if (instance? JarURLConnection conn)
        (let [jar (cast JarURLConnection conn)]
          (.getInputStream jar))
        (FileInputStream. (File. (.getFile url)))))))

(defn extract-resource [name dest-dir]
  (if-let [s (resource-stream name)]
    (let [dest (File. dest-dir name)]
      (.mkdirs (.getParentFile dest))
      (copy s dest)
      dest)
    (throw (Exception. (format "unable to find %s on classpath" name)))))

(defmacro with-streams [ins outs & forms]
  `(binding [*in*   (if ~ins  (LineNumberingPushbackReader. (InputStreamReader. ~ins)) *in*)
             *out*  (if ~outs (OutputStreamWriter. ~outs) *out*)
             *err*  (if ~outs (PrintWriter. ~outs true) *err*)
             *outs* ~outs
             *errs* ~outs
             *ins*  ~ins]
     ~@forms))

(defmacro init-log [log-file]
  (let [log (FileOutputStream. log-file true)]
    (System/setOut (PrintStream. log))
    (System/setErr (PrintStream. log))))

(defn disconnect [& [wait]]
  (let [outs *outs*]
    (future
      (when wait
        (Thread/sleep wait))
      (.close outs))))