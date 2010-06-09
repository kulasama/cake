(ns cake
  (:use clojure.useful cake.server
        [cake.project :only [init]])
  (:require [cake.ant :as ant])
  (:import [java.io InputStreamReader OutputStreamWriter BufferedReader]
           [java.net Socket ConnectException]))

; vars to be bound in each thread
(def run? nil)
(def opts nil)
(def project nil)

(defn verbose? [opts]
  (or (:v opts) (:verbose opts)))

(defn group [project]
  (if (or (= project 'clojure) (= project 'clojure-contrib))
    "org.clojure"
    (or (namespace project) (name project))))

(def cake-project (atom nil))

(defmacro defproject [project-name version & args]
  (let [root (.getParent (java.io.File. *file*))
        artifact (name project-name)]
    `(do (compare-and-set! cake-project nil
           (-> (apply hash-map '~args)
               (assoc :artifact-id ~artifact
                      :group-id    ~(group project-name)
                      :root        ~root
                      :version     ~version)
               (assoc-or :name         ~artifact
                         :library-path   (str ~root "/lib")
                         :compile-path   (str ~root "/classes")
                         :resources-path (str ~root "/resources")
                         :source-path    (str ~root "/src")
                         :test-path      (str ~root "/test"))))
         ; @cake-project must be set before we include the tasks for bake to work.
         (require 'cake.tasks.defaults))))

(defn dependency? [form]
  (or (symbol? form)
      (and (seq? form)
           (include? (first form) ['fn* 'fn]))))

(defn cat [s1 s2]
  (if s1 (str s1 " " s2) s2))

(def tasks (atom {}))

(defn update-task [task deps doc actions]
  {:pre [(every? dependency? deps) (every? fn? actions)]}
  (let [task (or task {:actions [] :deps []})]
    (-> task
        (update :deps    into deps)
        (update :doc     cat  doc)
        (update :actions into actions))))

(defmacro deftask
  "Define a cake task. Each part of the body is optional. Task definitions can
   be broken up among multiple deftask calls and even multiple files:
   (deftask foo => bar, baz ; => followed by prerequisites for this task
     \"Documentation for task.\"
     (do-something)
     (do-something-else))"
  [name & body]
  (let [[deps body] (if (= '=> (first body))
                      (split-with dependency? (rest body))
                      [() body])
        [doc actions] (if (string? (first body))
                        (split-at 1 body)
                        [nil body])
        actions (vec (map #(list 'fn [] %) actions))]
    `(swap! tasks update '~name update-task '~deps '~doc ~actions)))

(defn remove-task! [name]
  (swap! tasks dissoc name))

(defn run-task
  "Execute the specified task after executing all prerequisite tasks."
  [form]
  (if (list? form)
    (binding [project @cake-project] ((eval form))) ; excute anonymous dependency
    (let [name form, task (@tasks name)]
      (if (nil? task)
        (println "unknown task:" name)
        (when-not (run? task)
          (doseq [dep (:deps task)] (run-task dep))
          (binding [project @cake-project, ant/current-task name]
            (doseq [action (:actions task)] (action)))
          (set! run? (assoc run? name true)))))))

(def bake-port nil)

(defn- bake-connect [port]
  (loop []
    (if-let [socket (try (Socket. "localhost" (int port)) (catch ConnectException e))]
      socket
      (recur))))

(defn bake* [bindings body]
  (let [defs (for [[sym val] bindings] (list 'def sym (list 'quote val)))]
    (if (nil? bake-port)
      (println "bake not supported. perhaps you don't have a project.clj")
      (let [form  `(do ~@defs ~@body)
            socket (bake-connect (int bake-port))
            reader (BufferedReader. (InputStreamReader. (.getInputStream socket)))
            writer (OutputStreamWriter. (.getOutputStream socket))]
        (doto writer
          (.write (prn-str form))
          (.flush))
        (while-let [line (.readLine reader)]
                   (println line))
        (flush)))))

(defmacro bake
  "Execute body in a fork of the jvm with the classpath of your project."
  [bindings & body]
  (let [bindings (into ['opts 'cake/opts, 'project 'cake/project] bindings)
        bindings (for [[sym val] (partition 2 bindings)] [(list 'quote sym) val])]
    `(bake* [~@bindings] '~body)))

(defmacro task-doc
  "Print documentation for a task."
  [task]
  (println "-------------------------")
  (println "cake" (name task) " ;" (:doc (@tasks task))))

(defn process-command [form]
  (let [[task args port] form]
    (binding [ant/ant-project (ant/init-project @cake-project *outs*)
              opts (parse-args (keyword task) (map str args))
              bake-port port
              run? {}]
      (run-task (symbol (or task 'default))))))

(defn start-server [port]
  (init)
  (create-server port process-command))
