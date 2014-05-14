(ns goya.core
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [goog.dom :as dom]
            [goog.events :as events]
            [om.core :as om :include-macros true]
            [om.dom :as omdom :include-macros true]
            [goya.appstate :as app]
            [goya.timemachine :as timemachine]
            [goya.guistate :as guistate]
            [goya.previewstate :as previewstate]
            [goya.components.mainmenu :as mainmenu]
            [goya.components.toolsmenu :as toolsmenu]
            [goya.components.canvastools :as canvastools]
            [goya.components.palette :as palette]
            [goya.components.history :as history]
            [goya.components.canvas :as goyacanvas]
            [goya.components.drawing :as drawing]
            [goya.components.info :as info]
            [goya.canvasdrawing :as canvasdrawing]
            [cljs.core.async :refer [put! chan <! alts!]]))

(.log js/console "Welcome to Goya, the clojurescript pixel-art studio")

(enable-console-print!)

(events/listen js/document "keydown"
  #(let [event %
         keyCode (.-keyCode event)
         metaKey (.-metaKey event)
         shiftKey (.-shiftKey event)]
      (when (and (= keyCode 90) metaKey)
        (when shiftKey (timemachine/do-redo))
        (when-not shiftKey (timemachine/do-undo)))))

(defn maincomponent [app owner]
  (omdom/div nil
    (omdom/div #js {:id "leftAppColumn" :className "LeftColumnContainer"}
      (omdom/h1 #js {:className "app-title"}
        (:title (:info app))
        (omdom/h6 #js {:className "app-subtitle"}
          (str (:subtitle (:info app)) " / " (:version (:info app)))))
      (om/build toolsmenu/tools-menu-component (:tools app))
      (om/build canvastools/canvas-info-component app)
      (om/build mainmenu/menu-component app)
      (om/build info/infocomponent nil))

    (om/build canvastools/cursor-pos-component app)
    (om/build canvastools/grid-toggle-component app)
    (om/build palette/palette-component app)

    (om/build goyacanvas/canvas-minimap-component (:preview app))

    (omdom/div #js {:className "TimeMachineContainer"}
      (om/build history/header-component app)
      (om/build history/history-list-component (:undo-history (:main-app app))))

    (om/build goyacanvas/main-canvas-component (:main-app app))
    (om/build drawing/canvas-painting-component app)))

(om/root
  maincomponent
  app/app-state
  {:target (. js/document (getElementById "appcontainer"))
   :tx-listen #(timemachine/handle-transaction % %)})
