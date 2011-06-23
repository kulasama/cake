(ns cake.tasks.docs
  (:use cake
        [clojure.string :only [join]]
        (cake [core :only [deftask]]
              [task :only [tasks]])))

(deftask task-docs #{deps}
  "Generate documentation for tasks."
  "If a filename is passed, documentation is written to that file. Otherwise,
it is written to tasks.md in the current directory."
  (spit
   "tasks.md"
   (join
    (for [[k {:keys [docs deps]}] tasks]
      (str "#### " k "\n\n"
           (when (seq docs) (str (join "\n" docs) "\n\n"))
           (when (seq deps) (str "dependencies: " deps "\n\n")))))))