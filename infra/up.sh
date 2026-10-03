kind create cluster --config infra/kind-config.yaml
kubectl taint nodes -l nodemedic.io/role=spare nodemedic.io/spare=true:NoSchedule
