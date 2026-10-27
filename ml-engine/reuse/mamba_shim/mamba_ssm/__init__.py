"""
Прослойка вместо mamba_ssm (CUDA) для экспорта RE-USE в ONNX.
Слои и имена параметров — как в mamba_ssm 1.2.0 (Mamba v1), вычисления — как в «медленной»
эталонной ветке mamba_simple.Mamba.forward + selective_scan_ref, только на чистом PyTorch.
"""
from .modules.mamba_simple import Mamba, Block  # noqa: F401
