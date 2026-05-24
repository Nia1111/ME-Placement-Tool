---
navigation:
  parent: introduction/index.md
  title: ME設置ツール
  position: 1
  icon: meplacementtool:me_placement_tool
categories:
  - meplacementtool tools
item_ids:
  - meplacementtool:me_placement_tool
---

# ME設置ツール

<ItemImage id="meplacementtool:me_placement_tool" scale="4" />

ME設置ツールは、MEネットワークからブロックやAE2のケーブルパーツ、流体を直接設置できる強力なネットワーク統合ツールです。最大18種類のアイテムや流体を対象に登録することができます。

## 機能

- **ワイヤレス設置**: ブロックやパーツをインベントリに入れずに設置可能
- **ネットワーク統合**: <ItemLink id="ae2:wireless_access_point" />を介してMEネットワークに接続
- **18個の設定スロット**: 最大18種類のアイテムまたは流体を登録して、素早く切り替え可能
- **自動クラフト**: アイテムの在庫がなくてもクラフト可能な場合は、自動クラフト注文画面を開く
- **メモリーカード対応**: <ItemLink id="ae2:memory_card" />をオフハンドに持っている場合、デバイスを設置する際にその設定を自動的に適用
- **Mekanismの設定カード対応**: Mekanismの設定カードをオフハンドに持っている場合、デバイスを設置する際にその設定を自動的に適用

## 使用方法

- ***手に持って右クリック(空中で)**: 設定GUIを開く
- ***手に持って持って右クリック(ブロックに対して)**: ブロックを設置
- **Gキーを長押し**: ラジアルメニューを開く
- **ラジアルメニューで左クリック**: アイテムを選択

![ラジアルメニュー](../../assets/me_placement_tool_radial.png)

## ネットワーク接続

ツールを<ItemLink id="ae2:wireless_access_point" />にセットするとMEネットワークに接続できます。

## 設定GUI

![設定GUI](../../assets/me_placement_tool_gui.png)

設定GUIには、アイテムを配置するスロットが18個あります:
- JEI/REIからアイテムをドラッグ＆ドロップして対象に設定できます
- インベントリからアイテムをドラッグ＆ドロップして対象に設定できます
- ブロックだけでなく、AE2のパーツ（ケーブル、バス、パネルなど）も設定可能です

## レシピ

<RecipeFor id="meplacementtool:me_placement_tool" />
