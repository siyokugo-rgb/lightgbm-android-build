#!/usr/bin/env python3

import argparse
import csv
import gzip
import hashlib
import json
import platform
from pathlib import Path

import lightgbm as lgb
import numpy as np
import pandas as pd
import sklearn
from sklearn.metrics import (
    brier_score_loss,
    log_loss,
    roc_auc_score,
)


def sha256_file(path):
    h = hashlib.sha256()

    with path.open("rb") as f:
        for chunk in iter(
            lambda: f.read(1024 * 1024),
            b"",
        ):
            h.update(chunk)

    return h.hexdigest()


def write_json(path, obj):
    path.parent.mkdir(
        parents=True,
        exist_ok=True,
    )

    path.write_text(
        json.dumps(
            obj,
            ensure_ascii=False,
            indent=2,
            sort_keys=True,
        ) + "\n",
        encoding="utf-8",
    )


def load_manifest(path):
    with path.open(
        encoding="utf-8",
        newline="",
    ) as f:
        rows = list(csv.DictReader(f))

    if len(rows) != 60:
        raise ValueError(
            f"expected 60 manifest rows, got {len(rows)}"
        )

    return rows


def split_files(
    manifest,
    root,
    split,
):
    result = []

    for row in manifest:
        if row["split"] != split:
            continue

        path = root / row["file"]

        if not path.is_file():
            raise FileNotFoundError(path)

        result.append(
            (
                row["ym"],
                path,
                int(row["rows"]),
            )
        )

    if not result:
        raise ValueError(
            f"no files for split: {split}"
        )

    return result


def load_split(
    manifest,
    root,
    split,
    feature_names,
    target,
):
    files = split_files(
        manifest,
        root,
        split,
    )

    usecols = [
        "race_id",
        "entry_id",
        *feature_names,
        target,
    ]

    dtype = {
        name: "int32"
        for name in feature_names
    }

    # 空欄ラベルをNaNとして読むためfloat。
    dtype[target] = "float32"

    frames = []
    expected_rows = 0

    for index, (
        ym,
        path,
        manifest_rows,
    ) in enumerate(files, 1):

        frame = pd.read_csv(
            path,
            compression="gzip",
            usecols=usecols,
            dtype=dtype,
            keep_default_na=True,
        )

        if len(frame) != manifest_rows:
            raise ValueError(
                f"{ym}: row mismatch "
                f"{len(frame)} != {manifest_rows}"
            )

        frames.append(frame)

        expected_rows += manifest_rows

        if (
            index % 12 == 0
            or index == len(files)
        ):
            print(
                "loaded",
                split,
                index,
                "/",
                len(files),
                "through",
                ym,
            )

    frame = pd.concat(
        frames,
        ignore_index=True,
    )

    if len(frame) != expected_rows:
        raise ValueError(
            f"{split}: concat row mismatch"
        )

    source_rows = len(frame)

    valid = frame[target].notna()

    masked_rows = int(
        (~valid).sum()
    )

    frame = (
        frame.loc[valid]
        .reset_index(drop=True)
    )

    frame[target] = (
        frame[target]
        .astype("int8")
    )

    labels = set(
        frame[target]
        .unique()
        .tolist()
    )

    if not labels <= {0, 1}:
        raise ValueError(
            f"{split}: bad labels {labels}"
        )

    positive = int(
        frame[target].sum()
    )

    negative = (
        len(frame)
        - positive
    )

    print()
    print(
        split,
        "source_rows =",
        source_rows,
    )

    print(
        split,
        "masked_rows =",
        masked_rows,
    )

    print(
        split,
        "supervised_rows =",
        len(frame),
    )

    print(
        split,
        "positive =",
        positive,
    )

    print(
        split,
        "negative =",
        negative,
    )

    return (
        frame,
        source_rows,
        masked_rows,
    )


def global_metrics(
    labels,
    predictions,
):
    labels = np.asarray(
        labels,
        dtype=np.int8,
    )

    predictions = np.asarray(
        predictions,
        dtype=np.float64,
    )

    return {
        "rows": int(len(labels)),

        "positive": int(
            labels.sum()
        ),

        "negative": int(
            len(labels)
            - labels.sum()
        ),

        "log_loss": float(
            log_loss(
                labels,
                predictions,
                labels=[0, 1],
            )
        ),

        "brier_score": float(
            brier_score_loss(
                labels,
                predictions,
            )
        ),

        "roc_auc": float(
            roc_auc_score(
                labels,
                predictions,
            )
        ),

        "prediction_min": float(
            predictions.min()
        ),

        "prediction_max": float(
            predictions.max()
        ),

        "prediction_mean": float(
            predictions.mean()
        ),
    }


def calibration_metrics(
    labels,
    predictions,
    bins,
):
    labels = np.asarray(
        labels,
        dtype=np.int8,
    )

    predictions = np.asarray(
        predictions,
        dtype=np.float64,
    )

    bin_ids = np.floor(
        predictions * bins
    ).astype(np.int32)

    bin_ids = np.clip(
        bin_ids,
        0,
        bins - 1,
    )

    rows = []

    ece = 0.0

    total = len(labels)

    for index in range(bins):
        mask = (
            bin_ids == index
        )

        count = int(
            mask.sum()
        )

        if count == 0:
            continue

        mean_prediction = float(
            predictions[mask].mean()
        )

        observed = float(
            labels[mask].mean()
        )

        ece += (
            count
            / total
            * abs(
                mean_prediction
                - observed
            )
        )

        rows.append({
            "bin": index,
            "lower": index / bins,
            "upper": (
                index + 1
            ) / bins,
            "count": count,
            "mean_prediction":
                mean_prediction,
            "observed_win_rate":
                observed,
        })

    return {
        "bins_requested": bins,
        "populated_bins": len(rows),
        "expected_calibration_error":
            float(ece),
        "bins": rows,
    }


def race_metrics(
    race_ids,
    entry_ids,
    labels,
    predictions,
):
    frame = pd.DataFrame({
        "race_id": race_ids,
        "entry_id": entry_ids,
        "label": labels,
        "prediction": predictions,
    })

    race_count = 0

    top_hits = {
        1: 0,
        2: 0,
        3: 0,
    }

    probability_sums = []
    field_sizes = []
    winner_counts = []

    for _, group in frame.groupby(
        "race_id",
        sort=False,
    ):
        race_count += 1

        group = group.sort_values(
            [
                "prediction",
                "entry_id",
            ],
            ascending=[
                False,
                True,
            ],
            kind="mergesort",
        )

        winners = int(
            group["label"].sum()
        )

        if winners < 1:
            raise ValueError(
                "supervised race without winner"
            )

        winner_counts.append(
            winners
        )

        field_sizes.append(
            len(group)
        )

        probability_sums.append(
            float(
                group[
                    "prediction"
                ].sum()
            )
        )

        for k in (1, 2, 3):
            if (
                group
                .head(k)["label"]
                .sum()
                > 0
            ):
                top_hits[k] += 1

    sums = np.asarray(
        probability_sums,
        dtype=np.float64,
    )

    fields = np.asarray(
        field_sizes,
        dtype=np.int32,
    )

    winners = np.asarray(
        winner_counts,
        dtype=np.int32,
    )

    return {
        "races": int(
            race_count
        ),

        "top1_winner_rate": float(
            top_hits[1]
            / race_count
        ),

        "top2_winner_inclusion_rate":
            float(
                top_hits[2]
                / race_count
            ),

        "top3_winner_inclusion_rate":
            float(
                top_hits[3]
                / race_count
            ),

        "race_probability_sum": {
            "mean": float(
                sums.mean()
            ),
            "median": float(
                np.median(sums)
            ),
            "min": float(
                sums.min()
            ),
            "max": float(
                sums.max()
            ),
            "p05": float(
                np.quantile(
                    sums,
                    0.05,
                )
            ),
            "p95": float(
                np.quantile(
                    sums,
                    0.95,
                )
            ),
        },

        "field_size": {
            "mean": float(
                fields.mean()
            ),
            "min": int(
                fields.min()
            ),
            "max": int(
                fields.max()
            ),
        },

        "races_with_multiple_winners":
            int(
                np.sum(
                    winners > 1
                )
            ),
    }


def evaluate(
    name,
    frame,
    feature_names,
    target,
    model,
    best_iteration,
    calibration_bins,
):
    x = frame[
        feature_names
    ]

    labels = (
        frame[target]
        .to_numpy(
            dtype=np.int8
        )
    )

    predictions = model.predict(
        x,
        num_iteration=best_iteration,
    )

    overall = global_metrics(
        labels,
        predictions,
    )

    calibration = (
        calibration_metrics(
            labels,
            predictions,
            calibration_bins,
        )
    )

    races = race_metrics(
        frame["race_id"],
        frame["entry_id"],
        labels,
        predictions,
    )

    print()
    print(
        f"=== {name.upper()} ==="
    )

    print(
        "rows =",
        overall["rows"],
    )

    print(
        "positive =",
        overall["positive"],
    )

    print(
        "log_loss =",
        f"{overall['log_loss']:.8f}",
    )

    print(
        "brier =",
        f"{overall['brier_score']:.8f}",
    )

    print(
        "auc =",
        f"{overall['roc_auc']:.8f}",
    )

    print(
        "races =",
        races["races"],
    )

    print(
        "race top1 win =",
        f"{races['top1_winner_rate']:.6f}",
    )

    print(
        "race top2 include =",
        f"{races['top2_winner_inclusion_rate']:.6f}",
    )

    print(
        "race top3 include =",
        f"{races['top3_winner_inclusion_rate']:.6f}",
    )

    print(
        "race probability sum mean =",
        f"{races['race_probability_sum']['mean']:.6f}",
    )

    print(
        "ECE =",
        f"{calibration['expected_calibration_error']:.8f}",
    )

    return (
        {
            "global": overall,
            "calibration":
                calibration,
            "race": races,
        },
        predictions,
    )


def write_predictions(
    path,
    frame,
    target,
    predictions,
):
    out = pd.DataFrame({
        "race_id":
            frame["race_id"],
        "entry_id":
            frame["entry_id"],
        "label_win":
            frame[target],
        "prediction":
            predictions,
    })

    path.parent.mkdir(
        parents=True,
        exist_ok=True,
    )

    out.to_csv(
        path,
        index=False,
        compression={
            "method": "gzip",
            "mtime": 0,
        },
        lineterminator="\n",
    )


def create_android_fixture(
    path,
    frame,
    feature_names,
    predictions,
):
    race_id = (
        frame["race_id"]
        .iloc[0]
    )

    mask = (
        frame["race_id"]
        == race_id
    )

    race = (
        frame.loc[mask]
        .copy()
    )

    race_predictions = (
        predictions[
            mask.to_numpy()
        ]
    )

    entries = []

    for (_, row), prediction in zip(
        race.iterrows(),
        race_predictions,
    ):
        entries.append({
            "entry_id":
                row["entry_id"],

            "feature_vector": [
                int(
                    row[name]
                )
                for name in feature_names
            ],

            "expected_prediction":
                float(
                    prediction
                ),
        })

    write_json(
        path,
        {
            "version": 2,
            "race_id": race_id,
            "feature_count":
                len(feature_names),
            "feature_order":
                feature_names,
            "entries": entries,
        },
    )


def main():
    parser = argparse.ArgumentParser()

    parser.add_argument(
        "--dataset-root",
        type=Path,
        default=Path(
            "/workspaces/"
            "nar-v1-transformed"
        ),
    )

    parser.add_argument(
        "--manifest",
        type=Path,
        default=Path(
            "data-manifests/"
            "nar-v1-transformed/"
            "months.csv"
        ),
    )

    parser.add_argument(
        "--feature-order",
        type=Path,
        default=Path(
            "artifacts/nar-v2/"
            "feature-order.json"
        ),
    )

    parser.add_argument(
        "--category-dictionaries",
        type=Path,
        default=Path(
            "artifacts/nar-v1/"
            "category-dictionaries.json"
        ),
    )

    parser.add_argument(
        "--config",
        type=Path,
        default=Path(
            "config/"
            "nar-v2-win-no-identity4.json"
        ),
    )

    parser.add_argument(
        "--out",
        type=Path,
        default=Path(
            "/workspaces/"
            "nar-v2-models/"
            "win-no-identity4-v2"
        ),
    )

    args = parser.parse_args()

    cfg = json.loads(
        args.config.read_text(
            encoding="utf-8"
        )
    )

    feature_order = json.loads(
        args.feature_order.read_text(
            encoding="utf-8"
        )
    )

    dictionaries = json.loads(
        args.category_dictionaries.read_text(
            encoding="utf-8"
        )
    )

    manifest = load_manifest(
        args.manifest
    )

    feature_names = [
        item["name"]
        for item in feature_order[
            "features"
        ]
    ]

    categorical_names = (
        feature_order[
            "categorical_feature_names"
        ]
    )

    if len(feature_names) != 64:
        raise SystemExit(
            "expected 64 features"
        )

    if len(categorical_names) != 10:
        raise SystemExit(
            "expected 10 categorical features"
        )

    if dictionaries["fit_rows"] != 454102:
        raise SystemExit(
            "unexpected dictionary fit_rows"
        )

    target = cfg["target"]

    train_split = (
        cfg["splits"]["train"]
    )

    validation_split = (
        cfg["splits"]["validation"]
    )

    test_split = (
        cfg["splits"]["test"]
    )

    print(
        "LightGBM =",
        lgb.__version__,
    )

    print(
        "features =",
        len(feature_names),
    )

    print(
        "categorical =",
        len(categorical_names),
    )

    print()
    print("=== LOAD TRAIN ===")

    (
        train,
        train_source_rows,
        train_masked,
    ) = load_split(
        manifest,
        args.dataset_root,
        train_split,
        feature_names,
        target,
    )

    print()
    print(
        "=== LOAD VALIDATION ==="
    )

    (
        validation,
        validation_source_rows,
        validation_masked,
    ) = load_split(
        manifest,
        args.dataset_root,
        validation_split,
        feature_names,
        target,
    )

    x_train = train[
        feature_names
    ]

    y_train = train[target]

    x_validation = validation[
        feature_names
    ]

    y_validation = validation[
        target
    ]

    train_set = lgb.Dataset(
        x_train,
        label=y_train,
        feature_name=feature_names,
        categorical_feature=
            categorical_names,
        free_raw_data=True,
    )

    validation_set = lgb.Dataset(
        x_validation,
        label=y_validation,
        feature_name=feature_names,
        categorical_feature=
            categorical_names,
        reference=train_set,
        free_raw_data=True,
    )

    print()
    print("=== TRAIN ===")

    model = lgb.train(
        params=cfg[
            "lightgbm_params"
        ],
        train_set=train_set,
        num_boost_round=cfg[
            "training"
        ]["num_boost_round"],
        valid_sets=[
            validation_set,
        ],
        valid_names=[
            "validation",
        ],
        callbacks=[
            lgb.early_stopping(
                cfg["training"][
                    "early_stopping_rounds"
                ],
                first_metric_only=True,
                verbose=True,
            ),

            lgb.log_evaluation(
                cfg["training"][
                    "log_evaluation_period"
                ]
            ),
        ],
    )

    best_iteration = int(
        model.best_iteration
    )

    if best_iteration <= 0:
        raise SystemExit(
            "invalid best_iteration"
        )

    print()
    print(
        "best_iteration =",
        best_iteration,
    )

    args.out.mkdir(
        parents=True,
        exist_ok=True,
    )

    model_path = (
        args.out
        / "model.txt"
    )

    model.save_model(
        str(model_path),
        num_iteration=
            best_iteration,
    )

    print(
        "model sha256 =",
        sha256_file(
            model_path
        ),
    )

    (
        validation_metrics,
        validation_predictions,
    ) = evaluate(
        "validation",
        validation,
        feature_names,
        target,
        model,
        best_iteration,
        cfg["evaluation"][
            "calibration_bins"
        ],
    )

    write_predictions(
        args.out
        / "validation-predictions.csv.gz",
        validation,
        target,
        validation_predictions,
    )

    # 学習データ本体を先に解放。
    del train
    del x_train
    del y_train

    print()
    print("=== LOAD TEST ===")

    (
        test,
        test_source_rows,
        test_masked,
    ) = load_split(
        manifest,
        args.dataset_root,
        test_split,
        feature_names,
        target,
    )

    (
        test_metrics,
        test_predictions,
    ) = evaluate(
        "test",
        test,
        feature_names,
        target,
        model,
        best_iteration,
        cfg["evaluation"][
            "calibration_bins"
        ],
    )

    write_predictions(
        args.out
        / "test-predictions.csv.gz",
        test,
        target,
        test_predictions,
    )

    create_android_fixture(
        args.out
        / "android-parity-fixture.json",
        test,
        feature_names,
        test_predictions,
    )

    importance = pd.DataFrame({
        "feature":
            feature_names,

        "gain":
            model.feature_importance(
                importance_type="gain"
            ),

        "split":
            model.feature_importance(
                importance_type="split"
            ),
    })

    importance = (
        importance
        .sort_values(
            [
                "gain",
                "split",
                "feature",
            ],
            ascending=[
                False,
                False,
                True,
            ],
        )
    )

    importance.to_csv(
        args.out
        / "feature-importance.csv",
        index=False,
        lineterminator="\n",
    )

    metrics = {
        "version": 2,

        "model_name":
            cfg["model_name"],

        "target":
            target,

        "best_iteration":
            best_iteration,

        "source_rows": {
            "train":
                train_source_rows,
            "validation":
                validation_source_rows,
            "test":
                test_source_rows,
        },

        "masked_target_rows": {
            "train":
                train_masked,
            "validation":
                validation_masked,
            "test":
                test_masked,
        },

        "supervised_rows": {
            "train":
                train_source_rows
                - train_masked,

            "validation":
                validation_source_rows
                - validation_masked,

            "test":
                test_source_rows
                - test_masked,
        },

        "validation":
            validation_metrics,

        "test":
            test_metrics,

        "sha256": {
            "model":
                sha256_file(
                    model_path
                ),

            "feature_order":
                sha256_file(
                    args.feature_order
                ),

            "category_dictionaries":
                sha256_file(
                    args.category_dictionaries
                ),

            "training_config":
                sha256_file(
                    args.config
                ),

            "transformed_manifest":
                sha256_file(
                    args.manifest
                ),
        },

        "runtime": {
            "python":
                platform.python_version(),

            "lightgbm":
                lgb.__version__,

            "numpy":
                np.__version__,

            "pandas":
                pd.__version__,

            "scikit_learn":
                sklearn.__version__,
        },

        "lightgbm_params":
            cfg["lightgbm_params"],
    }

    write_json(
        args.out
        / "metrics.json",
        metrics,
    )

    write_json(
        args.out
        / "model-metadata.json",
        {
            "version": 2,

            "model_name":
                cfg["model_name"],

            "target":
                target,

            "feature_count":
                len(feature_names),

            "categorical_feature_count":
                len(
                    categorical_names
                ),

            "best_iteration":
                best_iteration,

            "model_file":
                "model.txt",

            "model_sha256":
                sha256_file(
                    model_path
                ),
        },
    )

    print()
    print("=== OUTPUT ===")

    for path in sorted(
        args.out.iterdir()
    ):
        if not path.is_file():
            continue

        print(
            path.name,
            path.stat().st_size,
            sha256_file(path),
        )

    print()
    print(
        "NAR V2 WIN NO_IDENTITY4 TRAINING OK"
    )


if __name__ == "__main__":
    main()
