(ns frontend.worker.web-server
  "Static application serving for the disk-backed browser runtime."
  (:require ["fs" :as fs]
            ["path" :as node-path]
            [clojure.string :as string]))

(def ^:private config-path "/web-server-config.js")

(def ^:private extension->content-type
  {".css" "text/css; charset=utf-8"
   ".gif" "image/gif"
   ".html" "text/html; charset=utf-8"
   ".ico" "image/x-icon"
   ".jpeg" "image/jpeg"
   ".jpg" "image/jpeg"
   ".js" "text/javascript; charset=utf-8"
   ".json" "application/json; charset=utf-8"
   ".map" "application/json; charset=utf-8"
   ".mjs" "text/javascript; charset=utf-8"
   ".otf" "font/otf"
   ".pdf" "application/pdf"
   ".png" "image/png"
   ".svg" "image/svg+xml"
   ".ttf" "font/ttf"
   ".wasm" "application/wasm"
   ".woff" "font/woff"
   ".woff2" "font/woff2"
   ".xml" "application/xml; charset=utf-8"})

(defn resolve-ui-dir!
  [ui-dir]
  (when (string/blank? ui-dir)
    (throw (ex-info "ui-dir is required" {:code :missing-ui-dir})))
  (let [requested-path (node-path/resolve ui-dir)]
    (try
      (let [resolved (fs/realpathSync requested-path)
            index-path (node-path/join resolved "index.html")]
        (when-not (.isDirectory (fs/statSync resolved))
          (throw (ex-info "ui-dir is not a directory"
                          {:code :invalid-ui-dir
                           :path resolved})))
        (when-not (and (fs/existsSync index-path)
                       (.isFile (fs/statSync index-path)))
          (throw (ex-info "ui-dir does not contain index.html"
                          {:code :invalid-ui-dir
                           :path resolved})))
        resolved)
      (catch :default error
        (if (= :invalid-ui-dir (:code (ex-data error)))
          (throw error)
          (throw (ex-info "ui-dir is not readable"
                          {:code :invalid-ui-dir
                           :path requested-path}
                          error)))))))

(defn- path-inside?
  [root path]
  (let [relative (node-path/relative root path)]
    (or (string/blank? relative)
        (and (not= relative "..")
             (not (string/starts-with? relative (str ".." node-path/sep)))
             (not (node-path/isAbsolute relative))))))

(defn resolve-static-file
  [ui-dir request-path]
  (try
    (let [decoded (js/decodeURIComponent request-path)
          relative (cond
                     (= "/" decoded)
                     "index.html"

                     (string/starts-with? decoded "/static/")
                     (subs decoded (count "/static/"))

                     :else
                     (string/replace-first decoded #"^/+" ""))
          candidate (node-path/resolve ui-dir relative)]
      (when (and (not (contains? #{"/static" "/static/"} decoded))
                 (not (string/includes? decoded "\u0000"))
                 (path-inside? ui-dir candidate)
                 (fs/existsSync candidate))
        (let [candidate (if (.isDirectory (fs/statSync candidate))
                          (node-path/join candidate "index.html")
                          candidate)
              real-path (when (fs/existsSync candidate)
                          (fs/realpathSync candidate))]
          (when (and real-path
                     (path-inside? ui-dir real-path)
                     (.isFile (fs/statSync real-path)))
            real-path))))
    (catch :default _
      nil)))

(defn- write-response!
  [^js res status headers body head?]
  (.writeHead res status (clj->js headers))
  (if head?
    (.end res)
    (.end res body)))

(defn- config-source
  [repo]
  (str "window.__LOGSEQ_WEB_SERVER__ = Object.freeze("
       (js/JSON.stringify (clj->js {:repo repo}))
       ");\n"))

(defn- serve-config!
  [^js res repo head?]
  (let [body (config-source repo)]
    (write-response! res 200
                     {"Cache-Control" "no-store"
                      "Content-Length" (js/Buffer.byteLength body "utf8")
                      "Content-Type" "text/javascript; charset=utf-8"}
                     body
                     head?)))

(defn- serve-file!
  [^js res file-path head?]
  (let [stat (fs/statSync file-path)
        content-type (get extension->content-type
                          (string/lower-case (node-path/extname file-path))
                          "application/octet-stream")]
    (.writeHead res 200
                #js {"Cache-Control" "no-cache"
                     "Content-Length" (.-size stat)
                     "Content-Type" content-type})
    (if head?
      (.end res)
      (.pipe (fs/createReadStream file-path) res))))

(defn handle-request!
  [^js req ^js res {:keys [ui-dir repo request-path]}]
  (let [method (.-method req)
        head? (= method "HEAD")]
    (cond
      (not (contains? #{"GET" "HEAD"} method))
      (write-response! res 405 {"Content-Type" "text/plain; charset=utf-8"}
                       "method-not-allowed" head?)

      (= request-path config-path)
      (serve-config! res repo head?)

      :else
      (if-let [file-path (resolve-static-file ui-dir request-path)]
        (serve-file! res file-path head?)
        (write-response! res 404 {"Content-Type" "text/plain; charset=utf-8"}
                         "not-found" head?)))))
