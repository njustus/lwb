; lwb Logic WorkBench -- Natural deduction

; Copyright (c) 2015 - 2016 Tobias Völzel, Burkhardt Renz, THM. All rights reserved.
; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php).
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.

(ns lwb.nd.io
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [lwb.consts :refer [rev]]
            [lwb.nd.rules :refer [gen-roth-relation roth-structure-f roth-structure-b]]
            [lwb.nd.proof :refer [proved?]]
            [lwb.nd.specs :refer :all]
            [clojure.spec :as s])
  (:import [java.io PushbackReader File]
           (java.time.format DateTimeFormatter)
           (java.time LocalDate)))

;; # Input and output of rules and theorems

;; These functions are used in lwb.nd.repl to load a logic and to export theorems
;; to files.

;; ## Helper functions

(defn- read-roths-from-resource
  "Reads sequence of roths from a resource file"
  [resource-name]
  (with-open [r (PushbackReader. (io/reader (io/resource resource-name)))]
    (doall (take-while some? (repeatedly #(edn/read {:eof nil} r))))))

(defn- read-roths-from-file
  "Reads sequence of roths from a file"
  [filename]
  (with-open [r (PushbackReader. (io/reader (io/file filename)))]
    (doall (take-while some? (repeatedly #(edn/read {:eof nil} r))))))

(defn- valid-rule?
  "Does the given map fulfill the spec of a rule?      
   Throws: Exception if not."
  [rule-map]
  (if (s/valid? :lwb.nd.specs/rule rule-map)
    true
    (throw (Exception. ^String (s/explain-str :lwb.nd.specs/rule rule-map)))))

(defn- valid-theorem?
  "Does the given map fulfill the spec of a theorem?      
   Throws: Exception if not."
  [theorem-map]
  (if (s/valid? :lwb.nd.specs/theorem theorem-map)
    true
    (throw (Exception. ^String (s/explain-str :lwb.nd.specs/theorem theorem-map)))))

(defn- import-v
  "Validated vector of roths from the import resource.      
   Returns: validated vector of rules or theorems respectively.    
   Throws: Exceptions if the resource can't be opened.    
           Exception if content of the resource is not valid."
  [name validation-fn]
  (filterv validation-fn (read-roths-from-resource name)))

(defn- import-file-v
  "Validated vector of roths from the import file.      
   Returns: validated vector of rules or theorems respectively.    
   Throws: Exceptions if the file can't be opened.    
           Exception if content of the file is not valid."
  [filename validation-fn]
  (filter validation-fn (read-roths-from-file filename)))

(defn- theorem
  "Returns: Map with the theorem from the `proof` with the given `id`.     
   Requires: The theorem is proved."
  [proof id]
  (let [given (vec (map :body (filter #(= (:roth %) :premise) (flatten proof))))
        conclusion (vector (:body (last proof)))]
    {:id id, :given given, :conclusion conclusion, :proof  proof}))

(defn- write-theorems
  "Writes the vector of theorems one by one in file named `filename`."
  [filename theorems-v]
  (let [date (.format (DateTimeFormatter/ofPattern "yyyy-MM-dd") (LocalDate/now))
        header (format "; Generated by lwb rev %s at %s\n; Do not edit.\n " rev date)
        content (reduce println-str theorems-v)]
    (spit filename (str header content))))

(defn- rule-fn
  "Generates rule entry from definition."
  [rule]
  (hash-map (:id rule)
            {:type       :rule
             :prereq     (:prereq rule)
             :given      (:given rule)
             :extra      (:extra rule)
             :conclusion (:conclusion rule)
             :forward    (roth-structure-f (:given rule) (:extra rule) (:conclusion rule))
             :backward   (roth-structure-b (:given rule) (:extra rule) (:conclusion rule))
             :logic-rel  (eval (gen-roth-relation (:prereq rule) (:given rule) (:extra rule)
                                                  (:conclusion rule)))}))

(defn- theorem-fn
  "Generates rule entry from definition."
  [theorem]
  (hash-map (:id theorem)
            {:type       :theorem
             :given      (:given theorem)
             :conclusion (:conclusion theorem)
             :forward    (roth-structure-f (:given theorem) (:extra theorem) (:conclusion theorem))
             :backward   (roth-structure-b (:given theorem) (:extra theorem) (:conclusion theorem))
             :logic-rel  (eval (gen-roth-relation nil (:given theorem) nil (:conclusion theorem)))}))
  
;; ## User Interface for lwb.nd.repl

(defn import-rules
  "Imports all rules from `resource-name` into global atom `roths`.     
   Requires: `resource-name` exists and has valid rules.     
   Modifies; global atom `roths`."
  [resource-name roths]
    (apply (partial swap! roths merge) (map rule-fn (import-v resource-name valid-rule?))))

(defn import-theorems
  "Imports all theorems from `name` into global atom `roths`.        
   If mode is `resource` the theorems are loaded from resources, from the file system otherwise
   Requires: `name` exists and has valid theorems.
   Modifies; global atom `roths`."
  ([resource-name roths] (import-theorems resource-name roths :resource))
  ([name roths mode]
    (apply (partial swap! roths merge) 
           (map theorem-fn (if (= mode :resource) (import-v name valid-theorem?) (import-file-v name valid-theorem?))))))

(defn import-theorem
  "Import theorems from `proof` under `id` into `roths`.      
   Modifies; global atom `roths`."
  [proof id roths]
  (if-not (proved? proof)
    (throw (Exception. "The proof is not completed yet.")))
  (if (id @roths)
    (throw (Exception. (format "There is already a theorem or rule with id '%s'." id))))
  (swap! roths conj [id (id (theorem-fn (theorem proof id)))]))

(defn export-theorem
    "Exports `proof` as a theorem with the `id` to `filename`.        
     If `mode` is not given it is `:check` and the function checks whether there is already a theorem
     with the given id in the file.   
     If `mode` is `:force` an already present theorem with the id is overwritten.
     Requires: `filename` is writeable and contains valid theorems.     
     Modifies: the file."
  ([proof filename id] (export-theorem proof filename id :check))
  ([proof filename id mode]
   (if-not (proved? proof)
     (throw (Exception. "The proof is not completed yet.")))
   ;; if there is no such file we generate it
   (.createNewFile ^File (io/as-file filename))
   (let [theorems-v (import-file-v filename valid-theorem?)
         ids (set (map :id theorems-v))
         already-there? (contains? ids id)]
     (if (and (= mode :check) already-there?)
       (throw (Exception. (format "There's already a theorem with the id %s. You may use the mode :force to overwrite it." id))))
     ;; insert in theorems-v
     (let [theorem (theorem proof id) 
           idx (first (keep-indexed #(when (= (:id %2) id) %1) theorems-v))
           theorems-v' (if idx (assoc theorems-v idx theorem) (conj theorems-v theorem))]
       (write-theorems filename theorems-v')))))
