(ns frontend.fs-test
  (:require ["fs" :as fs-node]
            ["path" :as node-path]
            [cljs.test :refer [is]]
            [clojure.string :as string]
            [frontend.config :as config]
            [frontend.fs :as fs]
            [frontend.persist-db.remote :as remote]
            [frontend.test.helper :as test-helper :include-macros true :refer [deftest-async]]
            [frontend.test.node-fixtures :as node-fixtures]
            [frontend.test.node-helper :as test-node-helper]
            [promesa.core :as p]))

(deftest-async web-server-assets-use-remote-disk-endpoints
  (let [runtime {:repo "logseq_db_graph"
                 :base-url "http://127.0.0.1:9123"}
        payload (js/ArrayBuffer. 3)
        calls (atom [])]
    (p/with-redefs [config/web-server-runtime (constantly runtime)
                    remote/<write-asset! (fn [runtime' repo file-name payload']
                                           (swap! calls conj [:write runtime' repo file-name payload'])
                                           (p/resolved nil))
                    remote/<read-asset (fn [runtime' repo file-name]
                                         (swap! calls conj [:read runtime' repo file-name])
                                         (p/resolved payload))
                    remote/<asset-exists? (fn [runtime' repo file-name]
                                            (swap! calls conj [:stat runtime' repo file-name])
                                            (p/resolved true))
                    remote/<list-assets (fn [runtime' repo]
                                          (swap! calls conj [:list runtime' repo])
                                          (p/resolved ["asset.png"]))]
      (p/let [_ (fs/write-asset-file! "logseq_db_graph" "asset.png" payload)
              read-result (fs/read-file-raw "memory:///graph" "assets/asset.png")
              stat-result (fs/stat "memory:///graph/assets/asset.png")
              files (fs/readdir "memory:///graph/assets" {:path-only? true})]
        (is (identical? payload read-result))
        (is (= "file" (.-type stat-result)))
        (is (= 1 (count files)))
        (is (string/ends-with? (first files) "/assets/asset.png"))
        (is (= [:write :read :stat :list] (mapv first @calls)))))))

(deftest-async create-if-not-exists-creates-correctly
  {:before (node-fixtures/setup-get-fs!)
   :after (node-fixtures/restore-get-fs!)}
  ;; dir needs to be an absolute path for fn to work correctly
  (let [dir (node-path/resolve (test-node-helper/create-tmp-dir))
        some-file (node-path/join dir "something.txt")]

    (->
     (p/do!
      (fs/create-if-not-exists nil nil some-file "NEW")
      (is (fs-node/existsSync some-file)
          "something.txt created correctly")
      (is (= "NEW"
             (str (fs-node/readFileSync some-file)))
          "something.txt has correct content"))

     (p/finally
       (fn []
         (fs-node/unlinkSync some-file)
         (fs-node/rmdirSync dir))))))

(deftest-async create-if-not-exists-does-not-create-correctly
  {:before (node-fixtures/setup-get-fs!)
   :after (node-fixtures/restore-get-fs!)}
  (let [dir (node-path/resolve (test-node-helper/create-tmp-dir))
        some-file (node-path/join dir "something.txt")]
    (fs-node/writeFileSync some-file "OLD")

    (->
     (p/do!
      (fs/create-if-not-exists nil nil some-file "NEW")
      (is (= "OLD" (str (fs-node/readFileSync some-file)))
          "something.txt has not been touched and old content still exists"))

     (p/finally
       (fn []
         (fs-node/unlinkSync some-file)
         (fs-node/rmdirSync dir))))))

(deftest-async write-plain-text-file-propagates-write-failure-test
  {:before (node-fixtures/setup-get-fs!)
   :after (node-fixtures/restore-get-fs!)}
  (let [dir (node-path/resolve (test-node-helper/create-tmp-dir))]
    (-> (fs/write-plain-text-file! nil dir dir "content" {})
        (p/then (fn [_]
                  (is false "Writing to a directory must reject.")))
        (p/catch (fn [error]
                   (is (some? error))))
        (p/finally #(fs-node/rmdirSync dir)))))
