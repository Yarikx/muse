(ns muse.deferred-spec
  (:require [clojure.test :refer (deftest is)]
            [muse.deferred :as muse :refer (fmap flat-map)]
            [muse.protocol :as proto]
            [manifold.deferred :as d])
  (:refer-clojure :exclude (run!)))

(defrecord DList [size]
  muse/DataSource
  (fetch [_] (d/future (range size)))
  muse/LabeledSource
  (resource-id [_] size))

(defrecord Single [seed]
  muse/DataSource
  (fetch [_] (d/future seed))
  muse/LabeledSource
  (resource-id [_] seed))

(defrecord SingleWithBatch [seed]
  muse/DataSource
  (fetch [_] (d/future seed))
  muse/BatchedSource
  (fetch-multi [this others] (->> (cons this others)
                                  (map :seed)
                                  (map (juxt identity identity))
                                  (into {})
                                  d/future))
  muse/LabeledSource
  (resource-id [_] seed))

(defrecord SingleWithIncBatch [seed]
  muse/DataSource
  (fetch [_] (d/future (inc seed)))
  muse/BatchedSource
  (fetch-multi [this others]
    (->> (cons this others) (map :seed) (map inc) d/future))
  muse/LabeledSource
  (resource-id [_] seed))

(defrecord Pair [seed]
  muse/DataSource
  (fetch [_] (d/future [seed seed]))
  muse/LabeledSource
  (resource-id [_] seed))

(defn- mk-pair [seed] (Pair. seed))

(defn- sum-pair [[a b]] (+ a b))

(defn- assert-ast
  ([expected ast] (assert-ast expected ast nil))
  ([expected ast callback]
   (is (= expected (muse/run!! ast)))))

(deftest datasource-ast
  (is (= 20 (count @(muse/run! (DList. 20)))))
  (is (= 20 (count (muse/run!! (DList. 20)))))
  (assert-ast 30 (fmap count (DList. 30)))
  (assert-ast 40 (fmap inc (fmap count (DList. 39))))
  (assert-ast 50 (fmap count (fmap concat (DList. 30) (DList. 20))))
  (assert-ast [15 15] (flat-map mk-pair (Single. 15)))
  (assert-ast 60 (fmap sum-pair (flat-map mk-pair (Single. 30)))))

(deftest higher-level-api
  (assert-ast [0 1] (muse/collect [(Single. 0) (Single. 1)]))
  (assert-ast [0 1 3 2 1 3] (muse/collect [(SingleWithBatch. 0)
                                           (SingleWithBatch. 1)
                                           (SingleWithBatch. 3)
                                           (SingleWithBatch. 2)
                                           (SingleWithBatch. 1)
                                           (SingleWithBatch. 3)]))
  (assert-ast [1 2 4 3 2 4] (muse/collect [(SingleWithIncBatch. 0)
                                           (SingleWithIncBatch. 1)
                                           (SingleWithIncBatch. 3)
                                           (SingleWithIncBatch. 2)
                                           (SingleWithIncBatch. 1)
                                           (SingleWithIncBatch. 3)]))
  (assert-ast [] (muse/collect []))
  (assert-ast [[0 0] [1 1]] (muse/traverse mk-pair (DList. 2)))
  (assert-ast [] (muse/traverse mk-pair (DList. 0))))

(defn- recur-next [seed]
  (if (= 5 seed)
    (muse/value seed)
    (flat-map recur-next (Single. (inc seed)))))

(deftest recur-with-value
  (assert-ast 10 (muse/value 10))
  (assert-ast 5 (flat-map recur-next (Single. 0))))

(deftest ast-with-no-fetches
  (assert-ast 42 (muse/flat-map muse/value (muse/value 42)))
  (assert-ast [43 43] (muse/flat-map mk-pair (muse/fmap inc (muse/value 42)))))

(deftest ast-failure-propagation
  (is (thrown? clojure.lang.ExceptionInfo
               (muse/run!! (muse/failure "Boom :("))))
  (is (thrown? clojure.lang.ExceptionInfo
               (muse/run!! (muse/fmap inc (muse/failure "Boom :(")))))
  (is (thrown? clojure.lang.ExceptionInfo
               (muse/run!! (muse/flat-map inc (muse/failure "Boom :(")))))
  (is (thrown? clojure.lang.ExceptionInfo
               (muse/run!! (muse/flat-map inc (muse/fmap inc (muse/failure "Boom :(")))))))

(extend-type clojure.lang.APersistentMap
  proto/FetchFailure
  (fetch-failed? [this] (contains? this :error))
  (failure-meta [this] this))

(defrecord MapWithError [reason]
  muse/DataSource
  (fetch [_] (d/success-deferred {:error reason}))
  muse/LabeledSource
  (resource-id [_] reason))

(defrecord MapWithoutError [value]
  muse/DataSource
  (fetch [_] (d/success-deferred {:value value}))
  muse/LabeledSource
  (resource-id [_] value))

(deftest custom-failure-propagation
  (is (thrown? clojure.lang.ExceptionInfo
               (muse/run!! (MapWithError. "Boom"))))
  (is (thrown? clojure.lang.ExceptionInfo
               (muse/run!! (muse/fmap merge
                                      (MapWithoutError. 10)
                                      (MapWithError. "Boom"))))))

(defrecord Slowpoke [id timer]
  muse/DataSource
  (fetch [_] (d/future (Thread/sleep timer) id)))

(deftest timeout-handling
  (is ::timeout (muse/run!! (Slowpoke. 1 25) 20 ::timeout))
  (is 2 (muse/run!! (Slowpoke. 2 25) 30 ::timeout)))
