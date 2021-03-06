(ns bake.test
  (:use clojure.test
        [cake :only [*config*]]
        [bake.core :only [verbose? log]]
        [bake.reload :only [last-reloaded last-modified reload]]
        [bake.notify :only [notify]])
  (:import [java.io StringWriter IOException]))

(def last-passed    (atom (System/currentTimeMillis)))
(def last-tested    (atom (System/currentTimeMillis)))
(def last-exception (atom 0))

(defn run? [opts ns]
  (let [tags       (set (opts :tags))
        functions  (set (opts :functions))
        namespaces (set (opts :namespaces))]
    (fn [[name f]]
      (and (:test (meta f))
           (or (:all opts)
               (namespaces ns)
               (some tags (:tags (meta f)))
               (functions (symbol (str ns "/" name))))))))

(defn wait-for-reload [interval]
  (while (or (< @last-reloaded @last-tested)
             (< @last-reloaded @last-exception))
    (Thread/sleep interval)
    (try (print ".") (flush)
         (reload)
         (reset! last-exception 0)
         (catch Throwable e
           (when (instance? IOException e) (throw e))
           (when (> @last-modified @last-exception)
             (notify (str (class e) ": " (.getMessage e))))
           (reset! last-exception (System/currentTimeMillis))))))

(defn run-ns-tests [opts ns]
  (when (verbose?)
    (log "About to run tests in" ns))
  (require ns)
  (when-let [tests (seq (filter (run? opts ns) (ns-publics ns)))]
    (let [ns-meta (meta (find-ns ns))
          once-fixtures (join-fixtures (:clojure.test/once-fixtures ns-meta))
          each-fixtures (join-fixtures (:clojure.test/each-fixtures ns-meta))]
      (binding [*test-out* (StringWriter.)
                *report-counters* (ref *initial-report-counters*)]
        (with-test-out
          (println "\ncake test" (ns-name ns)))
        (once-fixtures
         (fn []
           (doseq [[name f] tests]
             (when (verbose?)
               (log "Running test" name))
             (each-fixtures #(test-var f)))))
        (report (assoc @*report-counters* :type :summary))
        (let [failed?  (< 0 (apply + (map @*report-counters* [:fail :error])))
              test-out (.toString *test-out*)]
          (if (:autotest opts)
            (when failed?
              (notify test-out))
            (do (print test-out)
                (flush)))
          failed?)))))

(defn run-project-tests [namespaces opts]
  (when (:autotest opts)
    (wait-for-reload (* 1000 (Integer. (or (get *config* "autotest.interval") 5)))))
  (let [start    (System/currentTimeMillis)
        results  (doall (map (partial run-ns-tests opts) namespaces))
        failures (count (filter identity results))]
    (if (:autotest opts)
      (when (and (zero? failures)
                 (< @last-passed @last-tested))
        (notify "All tests passed"))
      (do (println "----")
          (println (if (zero? failures)
                     "All tests passed"
                     (str failures " test"
                          (when-not (= 1 failures)
                            "s")
                          " FAILED")))
          (println "Finished in" (/ (- (System/currentTimeMillis) start) 1000.0) "seconds.\n")))))
